package com.hmdp.dto.entity;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;
    private Long lastId;
    private Long offset;
}
