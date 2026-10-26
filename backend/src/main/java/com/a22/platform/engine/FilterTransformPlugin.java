package com.a22.platform.engine;

import com.a22.platform.engine.transform.TransformContext;
import com.a22.platform.engine.transform.TransformPlugin;
import com.a22.platform.engine.transform.TransformResult;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FilterTransformPlugin implements TransformPlugin {
    private final TaskExecutionEngine engine;

    public FilterTransformPlugin(@Lazy TaskExecutionEngine engine) {
        this.engine = engine;
    }

    @Override
    public String kind() {
        return "filter";
    }

    @Override
    public TransformResult transform(TransformContext context) throws Exception {
        List<Map<String, Object>> result = engine.applyFilter(context.getConfig(), context.getRecords());
        return new TransformResult(result);
    }
}
