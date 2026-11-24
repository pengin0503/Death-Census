package com.pengin0503.deathcensus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Pagination {
    public record Page<T>(List<T> items, int page, int pageCount, int total) {
        public Page {
            items = items == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    private Pagination() {
    }

    public static <T> Page<T> of(List<T> all, int requestedPage, int pageSize) {
        List<T> source = all == null ? List.of() : all;
        int size = pageSize < 1 ? 10 : pageSize;
        int total = source.size();
        int pageCount = (int) Math.max(1L, ((long) total + size - 1L) / size);
        int page = Math.max(1, Math.min(requestedPage, pageCount));
        int from = (page - 1) * size;
        int to = Math.min(total, from + size);
        List<T> items = from >= total
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(source.subList(from, to)));
        return new Page<>(items, page, pageCount, total);
    }
}
