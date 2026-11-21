package ai.aeiou.forwarder.booking.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import ai.aeiou.forwarder.booking.port.BookingPortal.BookingResult;
import ai.aeiou.forwarder.booking.port.BookingPortal.RateQuery;
import ai.aeiou.forwarder.booking.port.BookingPortal.RateResult;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestBookingPortalTest {

    private static final String BASE = "http://portal.test";
    private MockRestServiceServer server;
    private RestBookingPortal portal;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        portal = new RestBookingPortal(builder, BASE, "test-key");
    }

    @Test
    void bookedReturnsTheBooking() {
        server.expect(requestTo(BASE + "/v1/portal/bookings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "test-key"))
                .andExpect(content().json("{\"rate_id\":\"R-abcdefgh12\",\"team_reference\":\"AEI-7K2Q9\"}"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"booking_reference":"BK-7Q2M9X4L","team_reference":"AEI-7K2Q9","rate_id":"R-abcdefgh12",
                         "airline":"Helix Air Freight","airline_code":"HX","price":"1387.35","currency":"EUR","status":"booked"}"""));

        BookingResult result = portal.book("R-abcdefgh12", "AEI-7K2Q9");

        assertThat(result.booked()).isTrue();
        assertThat(result.booking().bookingReference()).isEqualTo("BK-7Q2M9X4L");
        assertThat(result.booking().price()).isEqualByComparingTo("1387.35");
        assertThat(result.booking().airlineCode()).isEqualTo("HX");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 410, 503})
    void errorStatusesAreReportedNotThrown(int status) {
        server.expect(requestTo(BASE + "/v1/portal/bookings"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"nope\"}"));

        BookingResult result = portal.book("R-abcdefgh12", "AEI-7K2Q9");

        assertThat(result.booked()).isFalse();
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.message()).contains("nope");
    }

    @Test
    void networkFailureIsUnreachable() {
        server.expect(requestTo(BASE + "/v1/portal/bookings")).andRespond(withException(new IOException("reset")));

        assertThat(portal.book("R-abcdefgh12", "AEI-7K2Q9").status()).isEqualTo(BookingPortal.UNREACHABLE);
    }

    @Test
    void rateParsesTheOffer() {
        server.expect(requestTo(BASE + "/v1/portal/rates?origin=FRA&destination=SIN&airline=HX&weight_kg=250&volume_cbm=1.2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "test-key"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("""
                        {"rate_id":"R-newrate0001","airline":"Helix Air Freight","airline_code":"HX","origin":"FRA",
                         "destination":"SIN","weight_kg":"250","chargeable_weight_kg":"250","total":"1390.00","currency":"EUR",
                         "transit_days":4,"valid_until":"2026-11-21T16:15:00Z","dataset_version":"v1"}"""));

        RateResult result = portal.rate(new RateQuery("FRA", "SIN", "HX", new BigDecimal("250"), new BigDecimal("1.2")));

        assertThat(result.ok()).isTrue();
        assertThat(result.rate().rateId()).isEqualTo("R-newrate0001");
        assertThat(result.rate().total()).isEqualByComparingTo("1390.00");
        assertThat(result.rate().transitDays()).isEqualTo(4);
    }
}
