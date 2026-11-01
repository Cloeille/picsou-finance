package com.picsou.support;

import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserRole;

/** Member and user shapes that several test classes build identically. */
public final class TestEntities {

    private TestEntities() {
    }

    /** The member every bank-session test hangs its session on. */
    public static FamilyMember owner() {
        return FamilyMember.builder().id(7L).displayName("Owner").build();
    }

    /** A user whose id doubles as its member id; only an admin is not a managed member. */
    public static AppUser user(long memberId, UserRole role, boolean activated) {
        return AppUser.builder()
            .id(memberId)
            .username("user" + memberId)
            .role(role)
            .activated(activated)
            .member(FamilyMember.builder().id(memberId).managed(role != UserRole.ADMIN).build())
            .build();
    }
}
