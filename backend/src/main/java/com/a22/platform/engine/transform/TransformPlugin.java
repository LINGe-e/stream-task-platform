package com.a22.platform.engine.transform;

public interface TransformPlugin {
    String kind();

    TransformResult transform(TransformContext context) throws Exception;
}
