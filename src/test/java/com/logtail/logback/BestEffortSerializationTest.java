package com.logtail.logback;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * For anything that serializes fine, the module must be invisible: its output has to be
 * byte-identical to a plain ObjectMapper, including for Jackson annotations that change
 * how a serializer is used ({@code @JsonUnwrapped}) or when a value is included
 * ({@code @JsonInclude(NON_EMPTY)}).
 */
public class BestEffortSerializationTest {

    private final ObjectMapper plain = new ObjectMapper();
    private final ObjectMapper bestEffort = new ObjectMapper().registerModule(new BestEffortSerialization());

    public static class Name {
        public String getFirst() {
            return "Ada";
        }

        public String getLast() {
            return "Lovelace";
        }
    }

    public static class UnwrappedName {
        @JsonUnwrapped
        public Name getName() {
            return new Name();
        }
    }

    public static class NonEmptyItems {
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        public List<String> getItems() {
            return new ArrayList<>();
        }
    }

    @Test
    public void testUnwrappedPropertiesStayFlattened() throws Exception {
        assertEquals(plain.writeValueAsString(new UnwrappedName()),
                bestEffort.writeValueAsString(new UnwrappedName()));
    }

    @Test
    public void testEmptyValueInclusionIsRespected() throws Exception {
        assertEquals(plain.writeValueAsString(new NonEmptyItems()),
                bestEffort.writeValueAsString(new NonEmptyItems()));
    }
}
