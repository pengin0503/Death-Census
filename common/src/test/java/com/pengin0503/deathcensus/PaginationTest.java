package com.pengin0503.deathcensus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaginationTest {

    @Test
    void emptyAndNullListsReturnSingleEmptyPage() {
        Pagination.Page<String> empty = Pagination.of(List.of(), 3, 10);
        assertTrue(empty.items().isEmpty());
        assertEquals(1, empty.page());
        assertEquals(1, empty.pageCount());
        assertEquals(0, empty.total());

        Pagination.Page<String> fromNull = Pagination.of(null, 0, 5);
        assertTrue(fromNull.items().isEmpty());
        assertEquals(1, fromNull.page());
        assertEquals(1, fromNull.pageCount());
        assertEquals(0, fromNull.total());
    }

    @Test
    void clampsRequestedPageAndSlicesAccurately() {
        List<String> items = List.of("a", "b", "c", "d", "e");

        Pagination.Page<String> below = Pagination.of(items, -2, 2);
        assertEquals(1, below.page());
        assertEquals(3, below.pageCount());
        assertEquals(5, below.total());
        assertEquals(List.of("a", "b"), below.items());

        Pagination.Page<String> middle = Pagination.of(items, 2, 2);
        assertEquals(2, middle.page());
        assertEquals(List.of("c", "d"), middle.items());

        Pagination.Page<String> above = Pagination.of(items, 99, 2);
        assertEquals(3, above.page());
        assertEquals(List.of("e"), above.items());
    }

    @Test
    void invalidPageSizeDefaultsToTen() {
        List<Integer> nums = IntStream.rangeClosed(1, 25).boxed().toList();
        Pagination.Page<Integer> page = Pagination.of(nums, 2, 0);
        assertEquals(2, page.page());
        assertEquals(3, page.pageCount());
        assertEquals(25, page.total());
        assertEquals(10, page.items().size());
        assertEquals(11, page.items().get(0));
        assertEquals(20, page.items().get(9));
    }
}
