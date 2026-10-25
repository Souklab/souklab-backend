package com.project.souklab.dao.specification;

import com.project.souklab.model.AccountStatus;
import com.project.souklab.model.User;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSpecificationsTest {

    @Mock
    private Root<User> root;

    @Mock
    private CriteriaQuery<?> query;

    @Mock
    private CriteriaBuilder cb;

    @Test
    @DisplayName("notDeleted: asserts deletedAt is null")
    void notDeleted_createsIsNullPredicate() {
        Path<Object> deletedAtPath = mock(Path.class);
        when(root.get("deletedAt")).thenReturn(deletedAtPath);
        Predicate isNullPredicate = mock(Predicate.class);
        when(cb.isNull(deletedAtPath)).thenReturn(isNullPredicate);

        Specification<User> spec = UserSpecifications.notDeleted();
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(isNullPredicate);
        verify(cb).isNull(deletedAtPath);
    }

    @Test
    @DisplayName("hasStatus: returns conjunction when null, equal predicate when present")
    void hasStatus_createsEqualPredicate() {
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        assertThat(UserSpecifications.hasStatus(null).toPredicate(root, query, cb))
                .isSameAs(conjunction);

        Path<Object> statusPath = mock(Path.class);
        when(root.get("status")).thenReturn(statusPath);
        Predicate equalPredicate = mock(Predicate.class);
        when(cb.equal(statusPath, AccountStatus.ACTIVE)).thenReturn(equalPredicate);

        Predicate result = UserSpecifications.hasStatus(AccountStatus.ACTIVE).toPredicate(root, query, cb);
        assertThat(result).isSameAs(equalPredicate);
    }

    @Test
    @DisplayName("searchKeyword: returns conjunction when blank, or predicate across fields when keyword provided")
    void searchKeyword_createsOrPredicate() {
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        assertThat(UserSpecifications.searchKeyword("   ").toPredicate(root, query, cb))
                .isSameAs(conjunction);

        Path<String> emailPath = mock(Path.class);
        Path<String> firstNamePath = mock(Path.class);
        Path<String> lastNamePath = mock(Path.class);
        when(root.<String>get("email")).thenReturn(emailPath);
        when(root.<String>get("firstName")).thenReturn(firstNamePath);
        when(root.<String>get("lastName")).thenReturn(lastNamePath);

        when(cb.lower(emailPath)).thenReturn(emailPath);
        when(cb.lower(firstNamePath)).thenReturn(firstNamePath);
        when(cb.lower(lastNamePath)).thenReturn(lastNamePath);

        Predicate p1 = mock(Predicate.class);
        Predicate p2 = mock(Predicate.class);
        Predicate p3 = mock(Predicate.class);
        when(cb.like(eq(emailPath), eq("%karim%"))).thenReturn(p1);
        when(cb.like(eq(firstNamePath), eq("%karim%"))).thenReturn(p2);
        when(cb.like(eq(lastNamePath), eq("%karim%"))).thenReturn(p3);

        Predicate orPredicate = mock(Predicate.class);
        when(cb.or(p1, p2, p3)).thenReturn(orPredicate);

        Predicate result = UserSpecifications.searchKeyword("Karim").toPredicate(root, query, cb);
        assertThat(result).isSameAs(orPredicate);
    }
}
