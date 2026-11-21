package ai.aeiou.forwarder.api;

import ai.aeiou.forwarder.api.dto.Carrier;
import ai.aeiou.forwarder.api.dto.Setting;
import ai.aeiou.forwarder.api.dto.TariffDetail;
import ai.aeiou.forwarder.api.dto.TariffSummary;
import ai.aeiou.forwarder.api.port.TariffActions;
import ai.aeiou.forwarder.api.query.ReferenceQueries;
import ai.aeiou.forwarder.settings.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tariff review, carriers and runtime settings. */
@RestController
@RequestMapping("/api")
public class ConfigController {

    private static final String HUMAN = "human";

    private final ReferenceQueries reference;
    private final TariffActions tariffActions;
    private final SettingsService settings;

    public ConfigController(ReferenceQueries reference, TariffActions tariffActions, SettingsService settings) {
        this.reference = reference;
        this.tariffActions = tariffActions;
        this.settings = settings;
    }

    @GetMapping("/tariffs")
    @Tag(name = "tariffs")
    @Operation(operationId = "listTariffs", summary = "All tariff versions, review queue first")
    public List<TariffSummary> tariffs() {
        return reference.tariffs();
    }

    @GetMapping("/tariffs/{id}")
    @Tag(name = "tariffs")
    @Operation(operationId = "getTariff")
    public TariffDetail tariff(@PathVariable long id) {
        return reference.tariffDetail(id).orElseThrow(() -> ApiException.notFound("Tariff", id));
    }

    @PostMapping("/tariffs/{id}/activate")
    @Tag(name = "tariffs")
    @Operation(operationId = "activateTariff", summary = "Activate a pending tariff version")
    public TariffSummary activate(@PathVariable long id) {
        tariffActions.activate(id);
        return reference.tariff(id).orElseThrow(() -> ApiException.notFound("Tariff", id));
    }

    @PostMapping("/tariffs/{id}/reject")
    @Tag(name = "tariffs")
    @Operation(operationId = "rejectTariff", summary = "Reject a pending tariff version")
    public TariffSummary reject(@PathVariable long id) {
        tariffActions.reject(id);
        return reference.tariff(id).orElseThrow(() -> ApiException.notFound("Tariff", id));
    }

    @GetMapping("/carriers")
    @Tag(name = "carriers")
    @Operation(operationId = "listCarriers")
    public List<Carrier> carriers() {
        return reference.carriers();
    }

    @GetMapping("/settings")
    @Tag(name = "settings")
    @Operation(operationId = "listSettings")
    public List<Setting> listSettings() {
        return reference.settings();
    }

    /** The settings service writes the one {@code setting.changed} event (actor human, with the previous value). */
    @PutMapping("/settings/{key}")
    @Tag(name = "settings")
    @Operation(operationId = "updateSetting", summary = "Change a runtime setting; takes effect immediately")
    public Setting updateSetting(@PathVariable String key, @RequestBody Setting.Update update) {
        if (update.value() == null) throw ApiException.badRequest("value is required");
        settings.set(key, update.value(), HUMAN);
        return reference.setting(key).orElseThrow(() -> ApiException.notFound("Setting", key));
    }
}
