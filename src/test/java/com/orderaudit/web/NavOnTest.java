package com.orderaudit.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderaudit.web.NavigationAdvice.Nav;
import org.junit.jupiter.api.Test;

/** The menu highlights the section the user is in, and nothing else. */
class NavOnTest {

    private static Nav at(String path) {
        return new Nav("ravi", true, true, true, true, true, true, path);
    }

    @Test
    void aSectionMatchesItsOwnPageAndItsSubPages() {
        assertThat(at("/sales/orders").on("/sales")).isTrue();
        assertThat(at("/sales/orders/new").on("/sales")).isTrue();
        assertThat(at("/admin/orders/7").on("/admin/orders")).isTrue();
    }

    @Test
    void aSectionDoesNotMatchAnotherSection() {
        assertThat(at("/admin/invoices").on("/admin/orders")).isFalse();
        assertThat(at("/production").on("/stock")).isFalse();
        // "/stock" must not match "/stockings"-style paths either: only the whole segment counts
        assertThat(at("/stocktake").on("/stock")).isFalse();
    }

    @Test
    void homeOnlyMatchesTheHomePageItself() {
        assertThat(at("/").on("/")).isTrue();
        assertThat(at("/reports").on("/")).isFalse();
        assertThat(new Nav(null, false, false, false, false, false, false, null).on("/sales")).isFalse();
    }
}
