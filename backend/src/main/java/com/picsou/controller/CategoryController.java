package com.picsou.controller;

import com.picsou.dto.CategoryRequest;
import com.picsou.dto.CategoryResponse;
import com.picsou.service.UserContext;
import com.picsou.service.budget.CategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Managed budget categories. The default set is seeded lazily on first read, so a brand
 * new member already sees a usable list from {@code GET /api/categories}.
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final UserContext userContext;

    public CategoryController(CategoryService categoryService, UserContext userContext) {
        this.categoryService = categoryService;
        this.userContext = userContext;
    }

    @GetMapping
    public List<CategoryResponse> findAll() {
        return categoryService.findAll(userContext.currentMemberId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@Valid @RequestBody CategoryRequest req) {
        return categoryService.create(req, userContext.currentMemberId());
    }

    @PutMapping("/{id}")
    public CategoryResponse update(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        return categoryService.update(id, req, userContext.currentMemberId());
    }

    /** Soft delete — archives the category rather than removing referenced history. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable Long id) {
        categoryService.archive(id, userContext.currentMemberId());
    }

    @PostMapping("/{id}/unarchive")
    public CategoryResponse unarchive(@PathVariable Long id) {
        return categoryService.unarchive(id, userContext.currentMemberId());
    }
}
