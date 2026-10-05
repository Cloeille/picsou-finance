package com.picsou.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * An account's ownership split.
 *
 * @param shares         explicit holders; empty means the owner implicitly holds everything
 * @param totalAssigned  sum of the shares
 * @param unassigned     {@code 100 - totalAssigned}: the part held outside Picsou (indivision
 *                       with a non-member, an SCI…). It belongs to nobody's net worth but is
 *                       still part of the property's gross value, so it is reported rather
 *                       than silently redistributed.
 */
public record OwnershipResponse(
    List<MemberShare> shares,
    BigDecimal totalAssigned,
    BigDecimal unassigned
) {
    public record MemberShare(
        Long memberId,
        String displayName,
        String avatarColor,
        BigDecimal sharePercent,
        boolean isOwner
    ) {}
}
