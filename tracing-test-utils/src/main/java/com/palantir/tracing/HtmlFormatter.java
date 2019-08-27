/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.tracing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.Resources;
import com.palantir.tracing.api.Span;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

final class HtmlFormatter {

    private static final ObjectWriter writer = new ObjectMapper().registerModule(new Jdk8Module()).writer();
    private final TimeBounds bounds;

    private HtmlFormatter(TimeBounds bounds) {
        this.bounds = bounds;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static void render(Builder builder) throws IOException {
        StringBuilder sb = new StringBuilder();

        HtmlFormatter formatter = new HtmlFormatter(TimeBounds.fromSpans(builder.spans));
        formatter.header(builder.displayName, sb);
        if (builder.chronological) {
            formatter.renderChronological(builder.spans, sb);
        } else {
            formatter.renderSplitByTraceId(builder.spans, sb);
        }
        formatter.rawSpanJson(builder.spans, sb);

        Files.write(builder.path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void renderChronologically(Collection<Span> spans, Path path, String displayName) throws IOException {
        StringBuilder sb = new StringBuilder();

        HtmlFormatter formatter = new HtmlFormatter(TimeBounds.fromSpans(spans));
        formatter.header(displayName, sb);
        formatter.renderChronological(spans, sb);
        formatter.rawSpanJson(spans, sb);

        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("JavaTimeDefaultTimeZone") // I actually want the system default time zone!
    private void header(String displayName, StringBuilder sb) throws IOException {
        sb.append(template("header.html", ImmutableMap.<String, String>builder()
                .put("{{DISPLAY_NAME}}", displayName)
                .put("{{DATE}}",
                        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                                .format(LocalDateTime.now(Clock.systemDefaultZone())))
                .build()));
    }

    private static String template(String resourceName, Map<String, String> values) {
        try {
            String template = Resources.toString(Resources.getResource(resourceName), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                template = template.replace(entry.getKey(), entry.getValue());
            }
            return template;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read resource " + resourceName, e);
        }
    }

    private void renderAllSpansForOneTraceId(String traceId, SpanAnalyzer.Result analysis, StringBuilder sb) {
        sb.append("<div style=\"border-top: 1px solid #E1E8ED\" title=\"" + traceId + "\">\n");
        analysis.orderedSpans().forEach(span -> {
            boolean suspectedCollision = analysis.collisions().contains(span);
            formatSpan(span, suspectedCollision, sb);
        });
        sb.append("</div>\n");
    }

    private void formatSpan(Span span, boolean suspectedCollision, StringBuilder sb) {
        long transposedStartMicros = span.getStartTimeMicroSeconds() - bounds.startMicros();

        long hue = Hashing.adler32().hashString(span.getTraceId(), StandardCharsets.UTF_8).padToLong() % 360;

        sb.append(template("span.html", ImmutableMap.<String, String>builder()
                .put("{{LEFT}}", Float.toString(Utils.percentage(transposedStartMicros, bounds.durationMicros())))
                .put("{{WIDTH}}", Float.toString(Utils.percentage(span.getDurationNanoSeconds(), bounds.durationNanos())))
                .put("{{HUE}}", Long.toString(hue))
                .put("{{TRACEID}}", span.getTraceId())
                .put("{{START}}", Utils.renderDuration(transposedStartMicros, TimeUnit.MICROSECONDS))
                .put("{{FINISH}}", Utils.renderDuration(transposedStartMicros + TimeUnit.MICROSECONDS.convert(
                        span.getDurationNanoSeconds(),
                        TimeUnit.NANOSECONDS), TimeUnit.MICROSECONDS))
                .put("{{OPERATION}}", span.getOperation())
                .put("{{DURATION}}", Utils.renderDuration(span.getDurationNanoSeconds(), TimeUnit.NANOSECONDS))
                .put("{{COLLISION}}", suspectedCollision ? " (collision)" : "")
                .build()));
    }


    private void rawSpanJson(Collection<Span> spans, StringBuilder sb) {
        sb.append("\n<pre style=\"background: #CED9E0;"
                + "color: #738694;"
                + "padding: 30px;"
                + "overflow-x: scroll;"
                + "margin-top: 100px;\">");
        spans.stream().sorted(Comparator.comparingLong(Span::getStartTimeMicroSeconds)).forEach(s -> {
            try {
                sb.append('\n');
                sb.append(writer.writeValueAsString(s));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Unable to JSON serialize span " + s, e);
            }
        });
        sb.append("\n</pre>");
    }

    private void renderChronological(Collection<Span> spans, StringBuilder sb) {
        spans.stream().sorted(Comparator.comparingLong(Span::getStartTimeMicroSeconds)).forEachOrdered(span -> {
            formatSpan(span, false, sb);
        });
    }

    private void renderSplitByTraceId(Collection<Span> spans, StringBuilder sb) {
        Map<String, List<Span>> spansByTraceId = spans.stream()
                .collect(Collectors.groupingBy(Span::getTraceId));

        Map<String, SpanAnalyzer.Result> analyzedByTraceId = Maps.transformValues(spansByTraceId, SpanAnalyzer::analyze);
        analyzedByTraceId.entrySet()
                .stream()
                .sorted(Comparator.comparingLong(e1 -> e1.getValue().bounds().startMicros()))
                .forEachOrdered(entry -> {
                    SpanAnalyzer.Result analysis = entry.getValue();
                    renderAllSpansForOneTraceId(entry.getKey(), analysis, sb);
                });
    }

    public static class Builder {
        private Collection<Span> spans;
        private Path path;
        private String displayName;
        private boolean chronological = true;
        private ImmutableList<String> problemSpanIds;

        public Builder spans(Collection<Span> spans) {
            this.spans = spans;
            return this;
        }

        public Builder path(Path path) {
            this.path = path;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder chronological(boolean chronological) {
            this.chronological = chronological;
            return this;
        }

        public Builder problemSpanIds(ImmutableList<String> problemSpanIds) {
            this.problemSpanIds = problemSpanIds;
            return this;
        }

        public void buildAndFormat() throws IOException {
            HtmlFormatter.render(this);
        }
    }
}