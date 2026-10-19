package com.ridex.ridex_app.driver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ridex.ridex_app.support.AbstractApiTest;
import com.ridex.ridex_app.support.ApiClient.Response;
import com.ridex.ridex_app.user.Role;
import com.ridex.ridex_app.user.User;

/** Everything a driver does before taking rides: profile, documents, vehicles, review, availability. */
class DriverOnboardingIntegrationTest extends AbstractApiTest {

    private String newDriverWithProfile() {
        User user = createUser(Role.DRIVER);
        String token = tokenOf(user);
        expect(201, api().post("/api/drivers/me", token,
                json("{'licenseNumber':'%s','licenseExpiry':'%s'}", uniqueLicence(), LocalDate.now().plusYears(2))));
        return token;
    }

    // ------------------------------------------------------------------ documents

    @Test
    void documentUploadsAreCheckedByTheirRealContent() {
        String driver = newDriverWithProfile();

        // a program renamed "licence.pdf" is still a program: refused, and nothing is stored
        Response disguised = expect(400, api().upload("/api/drivers/me/documents", driver, "DRIVING_LICENSE",
                "licence.pdf", "MZ this is not a pdf".getBytes(StandardCharsets.ISO_8859_1)));
        assertThat(disguised.<String>json("$.message")).contains("PDF, JPEG and PNG");
        expect(400, api().upload("/api/drivers/me/documents", driver, "DRIVING_LICENSE", "empty.pdf", new byte[0]));
        expect(400, api().upload("/api/drivers/me/documents", driver, "NOT_A_TYPE", "licence.pdf", pdf()));
        assertThat(api().get("/api/drivers/me/documents", driver).<List<Object>>json("$")).isEmpty();

        // a file over 5 MB is refused cleanly (never a 500)
        byte[] big = new byte[5 * 1024 * 1024 + 10];
        System.arraycopy(pdf(), 0, big, 0, pdf().length);
        assertThat(api().upload("/api/drivers/me/documents", driver, "INSURANCE", "huge.pdf", big).status()).isIn(400, 413);

        // a real PDF is accepted
        Response ok = expect(201, api().upload("/api/drivers/me/documents", driver, "DRIVING_LICENSE", "../../my licence.pdf", pdf()));
        assertThat(ok.<String>json("$.contentType")).isEqualTo("application/pdf");
        assertThat(ok.<String>json("$.originalFilename")).isEqualTo("my licence.pdf");   // path stripped
        assertThat(ok.<Integer>json("$.sizeBytes")).isEqualTo(pdf().length);
    }

    @Test
    void uploadingTheSameTypeAgainReplacesTheDocument() {
        String driver = newDriverWithProfile();
        String first = expect(201, api().upload("/api/drivers/me/documents", driver, "DRIVING_LICENSE", "a.pdf", pdf())).id();

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        Response second = expect(201, api().upload("/api/drivers/me/documents", driver, "DRIVING_LICENSE", "b.png", png));

        assertThat(second.id()).isNotEqualTo(first);
        assertThat(second.<String>json("$.contentType")).isEqualTo("image/png");
        Response list = api().get("/api/drivers/me/documents", driver);
        assertThat(list.<List<String>>json("$[*].id")).containsExactly(second.id());   // the old one is gone
        expect(404, api().get("/api/drivers/me/documents/" + first + "/file", driver));
    }

    @Test
    void documentFilesCanOnlyBeReadByTheirOwnerAndAdmins() {
        Person admin = newAdmin();
        String owner = newDriverWithProfile();
        String stranger = newDriverWithProfile();
        String documentId = expect(201, api().upload("/api/drivers/me/documents", owner, "DRIVING_LICENSE", "l.pdf", pdf())).id();
        String profileId = api().get("/api/drivers/me", owner).id();

        assertThat(api().download("/api/drivers/me/documents/" + documentId + "/file", owner)).isEqualTo(pdf());
        // another driver asking for the same id gets "not found", exactly as if it did not exist
        expect(404, api().get("/api/drivers/me/documents/" + documentId + "/file", stranger));
        // a rider is not allowed in the driver area at all
        expect(403, api().get("/api/drivers/me/documents/" + documentId + "/file", newRider().token()));
        // the admin reviewing the application can read it, through the admin endpoint
        assertThat(api().download("/api/admin/drivers/" + profileId + "/documents/" + documentId + "/file", admin.token()))
                .isEqualTo(pdf());
        // ...but the document must belong to the profile named in the URL
        String otherProfile = api().get("/api/drivers/me", stranger).id();
        expect(404, api().get("/api/admin/drivers/" + otherProfile + "/documents/" + documentId + "/file", admin.token()));
    }

    @Test
    void documentsAreLockedOnceTheDriverIsApproved() {
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);
        String documentId = api().get("/api/drivers/me/documents", driver.token()).json("$[0].id");

        expect(409, api().upload("/api/drivers/me/documents", driver.token(), "INSURANCE", "i.pdf", pdf()));
        expect(409, api().delete("/api/drivers/me/documents/" + documentId, driver.token()));
    }

    // ------------------------------------------------------------------ review workflow

    @Test
    void aRejectedDriverSeesWhyCorrectsTheDetailsAndIsReviewedAgain() {
        Person admin = newAdmin();
        String driver = newDriverWithProfile();
        String profileId = api().get("/api/drivers/me", driver).id();
        expect(201, api().upload("/api/drivers/me/documents", driver, "DRIVING_LICENSE", "l.pdf", pdf()));

        // rejecting needs a reason
        expect(400, api().post("/api/admin/drivers/" + profileId + "/reject", admin.token(), "{}"));
        Response rejected = expect(200, api().post("/api/admin/drivers/" + profileId + "/reject", admin.token(),
                "{\"reason\":\"Licence number does not match the photo\"}"));
        assertThat(rejected.<String>json("$.approvalStatus")).isEqualTo("REJECTED");

        // the driver sees the reason
        Response mine = expect(200, api().get("/api/drivers/me", driver));
        assertThat(mine.<String>json("$.approvalStatus")).isEqualTo("REJECTED");
        assertThat(mine.<String>json("$.rejectionReason")).contains("does not match");
        // a rejected application cannot simply be approved without a correction
        expect(409, api().post("/api/admin/drivers/" + profileId + "/approve", admin.token(), null));

        // correcting the details sends it back to the queue and clears the old verdict
        Response resubmitted = expect(200, api().put("/api/drivers/me", driver,
                json("{'licenseNumber':'%s','licenseExpiry':'%s'}", uniqueLicence(), LocalDate.now().plusYears(3))));
        assertThat(resubmitted.<String>json("$.approvalStatus")).isEqualTo("PENDING_APPROVAL");
        assertThat(resubmitted.<String>json("$.rejectionReason")).isNull();

        assertThat(expect(200, api().post("/api/admin/drivers/" + profileId + "/approve", admin.token(), null))
                .<String>json("$.approvalStatus")).isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ vehicles

    @Test
    void vehicleRulesAreEnforced() {
        String driver = newDriverWithProfile();
        String registration = uniqueRegistration();
        String body = "{'category':'%s','make':'Maruti','model':'Dzire','color':'White','registrationNumber':'%s','manufactureYear':2021,'seats':%d}";

        // bad registration number format
        expect(400, api().post("/api/drivers/me/vehicles", driver, json(body, "CAB", "NOT A PLATE", 4)));
        // a bike carries one passenger
        expect(409, api().post("/api/drivers/me/vehicles", driver, json(body, "BIKE", registration, 3)));

        // the first vehicle is stored normalised and becomes active automatically
        Response first = expect(201, api().post("/api/drivers/me/vehicles", driver, json(body, "CAB", registration.toLowerCase(), 4)));
        assertThat(first.<String>json("$.registrationNumber")).isEqualTo(registration);
        assertThat(first.<Boolean>json("$.active")).isTrue();
        // the same plate cannot be registered twice, even by someone else, or written differently
        expect(409, api().post("/api/drivers/me/vehicles", driver, json(body, "CAB", registration, 4)));
        expect(409, api().post("/api/drivers/me/vehicles", newDriverWithProfile(), json(body, "CAB", registration, 4)));

        // more vehicles start inactive; at most three
        String second = expect(201, api().post("/api/drivers/me/vehicles", driver, json(body, "AUTO", uniqueRegistration(), 3))).id();
        expect(201, api().post("/api/drivers/me/vehicles", driver, json(body, "BIKE", uniqueRegistration(), 1)));
        expect(409, api().post("/api/drivers/me/vehicles", driver, json(body, "CAB", uniqueRegistration(), 4)));

        // activating one switches the other off: exactly one is active at any time
        Response activated = expect(200, api().post("/api/drivers/me/vehicles/" + second + "/activate", driver, null));
        assertThat(activated.<Boolean>json("$.active")).isTrue();
        Response list = api().get("/api/drivers/me/vehicles", driver);
        assertThat(list.<List<Boolean>>json("$[?(@.active == true)]")).hasSize(1);

        // the registration number cannot be changed; other details can
        expect(409, api().put("/api/drivers/me/vehicles/" + second, driver, json(body, "AUTO", uniqueRegistration(), 3)));
    }

    @Test
    void aDriverCannotRemoveTheVehicleTheyAreDrivingWhileOnline() {
        Person admin = newAdmin();
        DriverFixture driver = newOnlineDriver(admin, VehicleCategory.CAB, 20.0, 76.0);

        expect(409, api().delete("/api/drivers/me/vehicles/" + driver.vehicleId(), driver.token()));
        expect(200, api().post("/api/drivers/me/availability/offline", driver.token(), null));
        expect(204, api().delete("/api/drivers/me/vehicles/" + driver.vehicleId(), driver.token()));
        // with no active vehicle they cannot go online again
        expect(409, api().post("/api/drivers/me/availability/online", driver.token(),
                json("{'latitude':%s,'longitude':%s}", coord(20.0), coord(76.0))));
    }

    @Test
    void anotherDriversVehicleIsNeverReachable() {
        Person admin = newAdmin();
        DriverFixture owner = newApprovedDriver(admin, VehicleCategory.CAB);
        String stranger = newDriverWithProfile();

        expect(404, api().post("/api/drivers/me/vehicles/" + owner.vehicleId() + "/activate", stranger, null));
        expect(404, api().delete("/api/drivers/me/vehicles/" + owner.vehicleId(), stranger));
    }

    // ------------------------------------------------------------------ availability

    @Test
    void goingOnlineAndSharingLocationFollowTheRules() {
        Person admin = newAdmin();
        DriverFixture driver = newApprovedDriver(admin, VehicleCategory.CAB);
        String position = json("{'latitude':%s,'longitude':%s}", coord(21.0), coord(76.0));

        // sharing a location while offline is refused
        expect(409, api().put("/api/drivers/me/availability/location", driver.token(), position));
        assertThat(expect(200, api().get("/api/drivers/me/availability", driver.token())).<Boolean>json("$.online")).isFalse();

        expect(200, api().post("/api/drivers/me/availability/online", driver.token(), position));
        expect(204, api().put("/api/drivers/me/availability/location", driver.token(),
                json("{'latitude':%s,'longitude':%s}", coord(21.001), coord(76.001))));
        Response status = expect(200, api().get("/api/drivers/me/availability", driver.token()));
        assertThat(status.<Boolean>json("$.online")).isTrue();
        assertThat(status.<Boolean>json("$.hasActiveVehicle")).isTrue();

        // impossible coordinates are rejected
        expect(400, api().put("/api/drivers/me/availability/location", driver.token(),
                "{\"latitude\":123.0,\"longitude\":76.0}"));
        expect(200, api().post("/api/drivers/me/availability/offline", driver.token(), null));
    }
}
