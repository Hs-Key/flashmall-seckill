package com.flashmall.product.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.flashmall.common.result.Result;
import com.flashmall.product.entity.Product;
import com.flashmall.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "商品模块")
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "分页查询商品列表")
    @GetMapping("/list")
    public Result<IPage<Product>> listProducts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(productService.listProducts(page, size));
    }

    @Operation(summary = "查询商品详情")
    @GetMapping("/{id}")
    public Result<Product> getProduct(@PathVariable Long id) {
        Product product = productService.getProductById(id);
        if (product == null) {
            return Result.fail("商品不存在");
        }
        return Result.success(product);
    }
}
