package com.orderaudit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orderaudit.domain.AppSetting;
import com.orderaudit.repository.AppSettingRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SettingServiceTest {

    @Mock AppSettingRepository repository;
    @InjectMocks SettingService service;

    // Build (and stub) the AppSetting mock BEFORE passing it into another when(...):
    // stubbing a mock inside an unfinished when(...) causes UnfinishedStubbingException.
    private AppSetting settingWithValue(String value) {
        AppSetting setting = mock(AppSetting.class);
        when(setting.getValue()).thenReturn(value);
        return setting;
    }

    @Test
    void readsTheDefaultTolerance() {
        AppSetting setting = settingWithValue("3");
        when(repository.findById("variance_tolerance_pct")).thenReturn(Optional.of(setting));

        assertThat(service.defaultTolerancePct()).isEqualByComparingTo("3");
    }

    @Test
    void trimsWhitespaceAndAcceptsDecimals() {
        AppSetting setting = settingWithValue(" 2.5 ");
        when(repository.findById("variance_tolerance_pct")).thenReturn(Optional.of(setting));

        assertThat(service.defaultTolerancePct()).isEqualByComparingTo("2.5");
    }

    @Test
    void aMissingSettingIsAnError() {
        when(repository.findById("variance_tolerance_pct")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.defaultTolerancePct()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aBlankOrNullValueIsAnError() {
        AppSetting blank = settingWithValue("  ");
        when(repository.findById("variance_tolerance_pct")).thenReturn(Optional.of(blank));
        assertThatThrownBy(() -> service.defaultTolerancePct()).isInstanceOf(IllegalStateException.class);

        AppSetting nullValue = settingWithValue(null);
        when(repository.findById("variance_tolerance_pct")).thenReturn(Optional.of(nullValue));
        assertThatThrownBy(() -> service.defaultTolerancePct()).isInstanceOf(IllegalStateException.class);
    }
}
