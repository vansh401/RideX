package com.ridex.ridex_app.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** One place that turns ?page=&size= into a safe Pageable (negative pages and huge sizes are clamped). */
public final class Paging {

    public static final int MAX_PAGE_SIZE = 100;

    private Paging() {
    }

    public static Pageable of(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort);
    }
}
