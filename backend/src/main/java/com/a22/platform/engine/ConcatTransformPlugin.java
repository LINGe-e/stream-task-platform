package com.a22.platform.engine;

import com.a22.platform.engine.transform.TransformContext;
import com.a22.platform.engine.transform.TransformPlugin;
import com.a22.platform.engine.transform.TransformResult;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ConcatTransformPlugin implements TransformPlugin {
    private final TaskExecutionEngine engine;

    public ConcatTransformPlugin(@Lazy TaskExecutionEngine engine) {
        this.engine = engine;
    }

    @Override
    public String kind() {
        return "concat";
    }

    @Override
    public TransformResult transform(TransformContext context) throws Exception {
        List<Map<String, Object>> result = engine.applyConcat(context.getConfig(), context.getRecords());
        return new TransformResult(result);
    }
}
