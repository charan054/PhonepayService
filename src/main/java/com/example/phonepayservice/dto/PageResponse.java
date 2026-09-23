package com.example.phonepayservice.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A plain, stable JSON shape for a page of results. Spring Data's own Page/PageImpl is deliberately not
 * returned directly from a controller: its Jackson serialization is not meant to be relied on across versions.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /** Converts each entry (e.g. an entity to the DTO a caller should actually see) without touching the paging info. */
    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(content.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }
}
