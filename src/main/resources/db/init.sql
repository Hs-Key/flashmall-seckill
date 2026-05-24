-- FlashMall 数据库初始化脚本
CREATE DATABASE IF NOT EXISTS flashmall DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE flashmall;

-- ===========================
-- 用户表
-- ===========================
DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id          BIGINT          NOT NULL AUTO_INCREMENT    COMMENT '用户ID',
    username    VARCHAR(50)     NOT NULL                   COMMENT '用户名',
    password    VARCHAR(100)    NOT NULL                   COMMENT '密码（BCrypt）',
    nickname    VARCHAR(50)                                COMMENT '昵称',
    phone       VARCHAR(20)                                COMMENT '手机号',
    role        VARCHAR(20)     NOT NULL DEFAULT 'USER'    COMMENT '角色 USER/ADMIN',
    status      TINYINT         NOT NULL DEFAULT 1         COMMENT '状态 1正常 0禁用',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ===========================
-- 商品表
-- ===========================
DROP TABLE IF EXISTS t_product;
CREATE TABLE t_product (
    id           BIGINT          NOT NULL AUTO_INCREMENT   COMMENT '商品ID',
    name         VARCHAR(200)    NOT NULL                  COMMENT '商品名称',
    description  TEXT                                      COMMENT '商品描述',
    price        DECIMAL(10, 2)  NOT NULL                  COMMENT '原价',
    stock        INT             NOT NULL DEFAULT 0        COMMENT '总库存',
    image_url    VARCHAR(500)                              COMMENT '商品图片URL',
    status       TINYINT         NOT NULL DEFAULT 1        COMMENT '状态 1上架 0下架',
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';

-- ===========================
-- 秒杀活动表
-- ===========================
DROP TABLE IF EXISTS t_seckill_activity;
CREATE TABLE t_seckill_activity (
    id            BIGINT          NOT NULL AUTO_INCREMENT   COMMENT '活动ID',
    product_id    BIGINT          NOT NULL                  COMMENT '关联商品ID',
    seckill_price DECIMAL(10, 2)  NOT NULL                  COMMENT '秒杀价格',
    stock         INT             NOT NULL DEFAULT 0        COMMENT '秒杀库存',
    start_time    DATETIME        NOT NULL                  COMMENT '活动开始时间',
    end_time      DATETIME        NOT NULL                  COMMENT '活动结束时间',
    status        TINYINT         NOT NULL DEFAULT 0        COMMENT '状态 0待开始 1进行中 2已结束',
    version       INT             NOT NULL DEFAULT 0        COMMENT '乐观锁版本号',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_product (product_id),
    KEY idx_status_time (status, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';

-- ===========================
-- 订单表
-- ===========================
DROP TABLE IF EXISTS t_order;
CREATE TABLE t_order (
    id              VARCHAR(32)     NOT NULL                  COMMENT '订单ID（雪花算法）',
    user_id         BIGINT          NOT NULL                  COMMENT '用户ID',
    product_id      BIGINT          NOT NULL                  COMMENT '商品ID',
    activity_id     BIGINT          NOT NULL                  COMMENT '秒杀活动ID',
    product_name    VARCHAR(200)    NOT NULL                  COMMENT '商品名称（快照）',
    amount          DECIMAL(10, 2)  NOT NULL                  COMMENT '支付金额',
    status          TINYINT         NOT NULL DEFAULT 0        COMMENT '状态 0待支付 1已支付 2已发货 3已完成 4已取消',
    idempotent_key  VARCHAR(64)                               COMMENT '幂等键（防重复下单）',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    paid_at         DATETIME                                  COMMENT '支付时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotent (idempotent_key),
    KEY idx_user_status (user_id, status),
    KEY idx_activity (activity_id),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- ===========================
-- 测试数据
-- ===========================

-- 管理员账号（密码：admin123，BCrypt加密）
INSERT INTO t_user (username, password, nickname, role) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBpwTTyBo6Dywu', '管理员', 'ADMIN'),
('user1', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBpwTTyBo6Dywu', '测试用户1', 'USER'),
('user2', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBpwTTyBo6Dywu', '测试用户2', 'USER');

-- 商品数据
INSERT INTO t_product (name, description, price, stock, image_url) VALUES
('iPhone 16 Pro', '苹果最新旗舰手机，A18仿生芯片，钛金属边框', 9999.00, 1000, '/static/images/iphone16.jpg'),
('小米14 Ultra', '小米旗舰，徕卡影像，骁龙8 Gen3', 5999.00, 2000, '/static/images/mi14.jpg'),
('索尼 WH-1000XM5', '业界领先主动降噪耳机，30小时续航', 2499.00, 500, '/static/images/sony_wh1000.jpg'),
('Nintendo Switch OLED', '任天堂掌机，OLED屏幕，64GB存储', 2299.00, 800, '/static/images/switch.jpg'),
('戴森 V15 吸尘器', '激光探测，智能感应，强劲吸力', 4990.00, 300, '/static/images/dyson_v15.jpg');

-- 秒杀活动（活动时间设置为当前时间前后，方便测试）
INSERT INTO t_seckill_activity (product_id, seckill_price, stock, start_time, end_time, status) VALUES
(1, 6999.00, 100, NOW() - INTERVAL 1 HOUR, NOW() + INTERVAL 23 HOUR, 1),
(2, 3999.00, 50,  NOW() - INTERVAL 30 MINUTE, NOW() + INTERVAL 2 HOUR, 1),
(3, 1299.00, 200, NOW() + INTERVAL 1 HOUR, NOW() + INTERVAL 25 HOUR, 0),
(4, 1599.00, 80,  NOW() - INTERVAL 2 HOUR, NOW() + INTERVAL 1 HOUR, 1);
