package com.a22.platform.file;

import java.util.List;
import java.util.Map;

record ParsedFile(String fileType, List<Map<String, Object>> records) { }
