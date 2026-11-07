package org.involens_be.dto.response;

import com.google.gson.annotations.Expose;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** Identifies a supplier available in the caller's invoice filter. */
@Getter
@AllArgsConstructor
public class VendorListItemResponse {
    @Expose private Long id;
    @Expose private String name;
}
