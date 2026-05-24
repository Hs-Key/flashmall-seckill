"""
秒杀并发压测脚本

用法:
    python3 loadtest_seckill.py            # 用默认参数
    python3 loadtest_seckill.py -n 200 -a 1 # 200 个用户抢活动 1

依赖:
    pip install requests

鉴权处理:
    第一次运行会注册 N 个用户 (loadtest_001 ~ loadtest_NNN)，密码统一，
    然后并发登录拿 token，token 缓存到 ./.tokens.json。
    之后再跑就直接读缓存，几秒就能开打。
    Token 过期 (401) 时删掉 .tokens.json 重跑即可。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import Counter
from pathlib import Path

import requests

BASE_URL = "http://localhost:8080"
PASSWORD = "Test123456"          # 满足后端 6~20 位约束
USERNAME_PREFIX = "loadtest_"
TOKEN_CACHE = Path(__file__).parent / ".tokens.json"


# ---------- setup ----------

def ensure_user(idx: int) -> None:
    """注册一个用户，已存在则忽略。"""
    username = f"{USERNAME_PREFIX}{idx:04d}"
    payload = {
        "username": username,
        "password": PASSWORD,
        "nickname": f"压测{idx}",
        "phone": f"139{idx:08d}",
    }
    r = requests.post(f"{BASE_URL}/api/user/register", json=payload, timeout=5)
    # 后端约定：code=200 成功；其他 code 视为已存在/校验失败，忽略
    # （注册接口对"已存在"会返回业务错误码，不是 200）


def login(idx: int) -> tuple[int, str | None]:
    """登录拿 token。"""
    username = f"{USERNAME_PREFIX}{idx:04d}"
    r = requests.post(
        f"{BASE_URL}/api/user/login",
        json={"username": username, "password": PASSWORD},
        timeout=5,
    )
    data = r.json()
    if data.get("code") != 200:
        return idx, None
    return idx, data["data"]["accessToken"]


def prepare_tokens(n: int, workers: int) -> list[str]:
    """注册 + 登录，结果落盘缓存。返回 token 列表。"""
    if TOKEN_CACHE.exists():
        cached = json.loads(TOKEN_CACHE.read_text())
        if len(cached) >= n:
            print(f"[setup] 命中 token 缓存 ({len(cached)} 个)")
            return cached[:n]

    print(f"[setup] 注册 {n} 个用户...")
    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(ensure_user, range(1, n + 1)))

    print(f"[setup] 并发登录拿 token...")
    tokens: list[str | None] = [None] * n
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for idx, token in pool.map(login, range(1, n + 1)):
            tokens[idx - 1] = token

    failed = [i for i, t in enumerate(tokens) if t is None]
    if failed:
        raise RuntimeError(f"以下用户登录失败: {failed[:10]}{'...' if len(failed) > 10 else ''}")

    TOKEN_CACHE.write_text(json.dumps(tokens, ensure_ascii=False))
    print(f"[setup] 已写入 {TOKEN_CACHE}")
    return tokens


# ---------- seckill ----------

def seckill_once(token: str, activity_id: int) -> tuple[int, str]:
    """单次完整秒杀流程：拿幂等 token -> POST 秒杀。"""
    auth = {"Authorization": f"Bearer {token}"}
    try:
        tr = requests.get(f"{BASE_URL}/api/order/token", headers=auth, timeout=5).json()
        if tr.get("code") != 200:
            return tr.get("code", -1), f"获取幂等token失败: {tr.get('message')}"

        sr = requests.post(
            f"{BASE_URL}/api/seckill/do/{activity_id}",
            headers={**auth, "Idempotent-Token": tr["data"]},
            timeout=5,
        ).json()
        return sr.get("code", -1), sr.get("message") or str(sr)
    except requests.RequestException as e:
        return -1, f"network: {e}"


def run_seckill(tokens: list[str], activity_id: int, workers: int) -> None:
    n = len(tokens)
    print(f"\n[run] 并发 {workers} 线程，{n} 个请求打活动 {activity_id}\n")
    counter: Counter[str] = Counter()
    sample_messages: dict[int, str] = {}
    started = time.time()

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(seckill_once, t, activity_id) for t in tokens]
        for fut in as_completed(futures):
            code, msg = fut.result()
            key = f"{code} | {msg}"
            counter[key] += 1
            sample_messages.setdefault(code, msg)

    elapsed = time.time() - started
    print(f"完成: {n} 个请求, 耗时 {elapsed:.2f}s, QPS≈{n/elapsed:.0f}\n")
    print("响应分布:")
    for key, cnt in counter.most_common():
        print(f"  {cnt:>5}  {key}")


# ---------- main ----------

def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("-n", "--num-users", type=int, default=100, help="并发用户数")
    p.add_argument("-a", "--activity-id", type=int, default=1, help="秒杀活动 ID")
    p.add_argument("-w", "--workers", type=int, default=50, help="线程池大小")
    args = p.parse_args()

    tokens = prepare_tokens(args.num_users, args.workers)
    run_seckill(tokens, args.activity_id, args.workers)
    return 0


if __name__ == "__main__":
    sys.exit(main())
