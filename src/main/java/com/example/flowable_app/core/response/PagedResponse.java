package com.example.flowable_app.core.response;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

@Getter
@Builder
public class PagedResponse<T> {

    private final List<T> content;
    private final int currentPage;
    private final int totalPages;
    private final long totalElements;
    private final int pageSize;
    private final boolean isFirst;
    private final boolean isLast;

    public static <T> PagedResponse<T> of(
            List<T> content,
            int currentPage,
            int pageSize,
            long totalElements) {

        int totalPages = (int) Math.ceil((double) totalElements / pageSize);

        return PagedResponse.<T>builder()
                .content(content)
                .currentPage(currentPage)
                .pageSize(pageSize)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .isFirst(currentPage == 0)
                .isLast(currentPage >= totalPages - 1)
                .build();
    }
}