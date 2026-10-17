package com.ridex.ridex_app.rider;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class RiderRequestValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private SavedPlaceRequest place(String label, String address, String lat, String lng) {
        return new SavedPlaceRequest(label, address,
                lat == null ? null : new BigDecimal(lat), lng == null ? null : new BigDecimal(lng));
    }

    @Test
    void aNormalPlaceIsValid() {
        assertThat(VALIDATOR.validate(place("Home", "22 Windsor Lane", "12.971599", "77.594566"))).isEmpty();
    }

    @Test
    void coordinatesAtTheEdgesOfTheRangeAreValid() {
        assertThat(VALIDATOR.validate(place("N", "Pole", "90", "180"))).isEmpty();
        assertThat(VALIDATOR.validate(place("S", "Pole", "-90", "-180"))).isEmpty();
    }

    @Test
    void coordinatesOutsideTheRangeAreRejected() {
        assertThat(VALIDATOR.validate(place("X", "Nowhere", "90.5", "77"))).isNotEmpty();
        assertThat(VALIDATOR.validate(place("X", "Nowhere", "12", "180.1"))).isNotEmpty();
        assertThat(VALIDATOR.validate(place("X", "Nowhere", "-91", "77"))).isNotEmpty();
    }

    @Test
    void moreThanSixDecimalPlacesIsRejected() {
        assertThat(VALIDATOR.validate(place("X", "Nowhere", "12.1234567", "77"))).isNotEmpty();
    }

    @Test
    void labelAndAddressAreRequiredAndLimited() {
        assertThat(VALIDATOR.validate(place(" ", "22 Windsor Lane", "12", "77"))).isNotEmpty();
        assertThat(VALIDATOR.validate(place("Home", "", "12", "77"))).isNotEmpty();
        assertThat(VALIDATOR.validate(place("L".repeat(31), "22 Windsor Lane", "12", "77"))).isNotEmpty();
        assertThat(VALIDATOR.validate(place("Home", "A".repeat(256), "12", "77"))).isNotEmpty();
    }

    @Test
    void missingCoordinatesAreRejected() {
        assertThat(VALIDATOR.validate(place("Home", "22 Windsor Lane", null, "77"))).isNotEmpty();
        assertThat(VALIDATOR.validate(place("Home", "22 Windsor Lane", "12", null))).isNotEmpty();
    }

    @Test
    void emergencyContactNeedsBothFieldsOrNeither() {
        assertThat(VALIDATOR.validate(new UpdateRiderProfileRequest("Meera", "+919811122233"))).isEmpty();
        assertThat(VALIDATOR.validate(new UpdateRiderProfileRequest(null, null))).isEmpty();
        assertThat(VALIDATOR.validate(new UpdateRiderProfileRequest("Meera", null))).isNotEmpty();
        assertThat(VALIDATOR.validate(new UpdateRiderProfileRequest(null, "+919811122233"))).isNotEmpty();
    }

    @Test
    void emergencyPhoneMustLookLikeAPhoneNumber() {
        assertThat(VALIDATOR.validate(new UpdateRiderProfileRequest("Meera", "12345"))).isNotEmpty();
        assertThat(VALIDATOR.validate(new UpdateRiderProfileRequest("Meera", "not-a-number"))).isNotEmpty();
    }
}
