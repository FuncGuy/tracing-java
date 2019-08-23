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

import java.io.IOException;
import org.junit.Test;

public class HtmlSpanObserverTest {

    @Test
    public void name() throws InterruptedException, IOException {
        Tracer.setSampler(AlwaysSampler.INSTANCE);
        HtmlSpanObserver consumer = new HtmlSpanObserver();
        Tracer.subscribe("whatever", consumer);

        try (CloseableTracer root = CloseableTracer.startSpan("root")) {
            try (CloseableTracer first = CloseableTracer.startSpan("first")) {
                Thread.sleep(100);
            }
            try (CloseableTracer second = CloseableTracer.startSpan("second")) {
                Thread.sleep(100);
            }
            try (CloseableTracer third = CloseableTracer.startSpan("third")) {
                Thread.sleep(100);
            }
        }

        consumer.output();
    }
}
