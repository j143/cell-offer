package j143.github.celloffer.controller;

import j143.github.citrus.EvaluationContext;
import j143.github.citrus.Experiment;
import j143.github.citrus.ExperimentClient;
import j143.github.citrus.ExperimentRegistry;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/citrus")
@CrossOrigin(origins = "*")
public class ExperimentController {

    private final ExperimentClient experimentClient;

    public ExperimentController(ExperimentClient experimentClient) {
        this.experimentClient = experimentClient;
    }

    @GetMapping("/experiments")
    public Map<String, Object> listExperiments() {
        ExperimentRegistry registry = experimentClient.getCurrentRegistry();
        List<Map<String, Object>> experiments = registry.getExperiments().stream()
                .map(this::toExperimentSummary)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", experiments.size());
        response.put("experiments", experiments);
        return response;
    }

    @GetMapping("/probe")
    public Map<String, Object> probe(
            @RequestParam String paramKey,
            @RequestParam String unitType,
            @RequestParam String unitId,
            @RequestParam(defaultValue = "int") String kind,
            @RequestParam(defaultValue = "0") String defaultValue) {

        EvaluationContext ctx = new EvaluationContext().set(unitType, unitId);
        ExperimentRegistry registry = experimentClient.getCurrentRegistry();
        Experiment active = registry.getExperimentForParameter(paramKey).orElse(null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("paramKey", paramKey);
        response.put("unitType", unitType);
        response.put("unitId", unitId);
        response.put("kind", kind);
        response.put("active", active != null);

        if (active == null || !ctx.hasUnit(active.getUnitType())) {
            response.put("resolvedValue", resolveDefault(kind, defaultValue));
            response.put("source", "default");
            return response;
        }

        String resolvedUnitId = ctx.getUnitId(active.getUnitType());
        int bucket = j143.github.citrus.ExperimentHasher.getBucket(resolvedUnitId, active.getName());
        String variant = active.getVariantForBucket(bucket);

        response.put("experimentName", active.getName());
        response.put("variant", variant);
        response.put("bucket", bucket);

        if ("long".equalsIgnoreCase(kind)) {
            long resolved = experimentClient.getLongParam(paramKey, Long.parseLong(defaultValue), ctx);
            response.put("resolvedValue", resolved);
        } else {
            int resolved = experimentClient.getIntParam(paramKey, Integer.parseInt(defaultValue), ctx);
            response.put("resolvedValue", resolved);
        }

        response.put("source", "experiment");
        return response;
    }

    private Map<String, Object> toExperimentSummary(Experiment experiment) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", experiment.getName());
        summary.put("enabled", experiment.isEnabled());
        summary.put("unitType", experiment.getUnitType());
        summary.put("parameterKeys", experiment.getParameterKeys());
        return summary;
    }

    private Object resolveDefault(String kind, String defaultValue) {
        if ("long".equalsIgnoreCase(kind)) {
            return Long.parseLong(defaultValue);
        }
        return Integer.parseInt(defaultValue);
    }
}