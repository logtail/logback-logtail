package com.logtail.logback;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.ResolvableSerializer;
import com.fasterxml.jackson.databind.type.ArrayType;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.util.TokenBuffer;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Jackson module making serialization of arbitrary logged values best-effort instead of all-or-nothing,
 * in a single serialization pass. Values serialize into the same JSON as without this module, except that
 * data Jackson cannot handle is omitted and replaced with a marker string:
 * <ul>
 * <li>a reference back to an object the serializer is currently inside (a cyclic object graph, e.g. a
 * pooled JDBC connection) is replaced with {@code "<omitted circular reference>"} while the rest of the
 * object stays intact,</li>
 * <li>a value wrapped with {@link #guard(Object)} whose serialization fails for any other reason
 * (typically a getter that throws) is replaced as a whole with a marker naming its class.</li>
 * </ul>
 */
public class BestEffortSerialization extends SimpleModule {

    static final String CIRCULAR_REFERENCE_MARKER = "<omitted circular reference>";

    private static final Object ANCESTORS = new Object();

    public BestEffortSerialization() {
        addSerializer(Guarded.class, new GuardedSerializer());
        setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc, JsonSerializer<?> serializer) {
                return new CycleGuard(serializer);
            }

            @Override
            public JsonSerializer<?> modifyArraySerializer(SerializationConfig config, ArrayType valueType, BeanDescription beanDesc, JsonSerializer<?> serializer) {
                return new CycleGuard(serializer);
            }

            @Override
            public JsonSerializer<?> modifyCollectionSerializer(SerializationConfig config, CollectionType valueType, BeanDescription beanDesc, JsonSerializer<?> serializer) {
                return new CycleGuard(serializer);
            }

            @Override
            public JsonSerializer<?> modifyMapSerializer(SerializationConfig config, MapType valueType, BeanDescription beanDesc, JsonSerializer<?> serializer) {
                return new CycleGuard(serializer);
            }
        });
    }

    /**
     * Wraps a value of an unknown type so that when its serialization fails, it is replaced with a marker
     * string instead of failing the serialization of everything around it.
     */
    public static Object guard(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return new Guarded(value);
    }

    static final class Guarded {
        final Object value;

        Guarded(Object value) {
            this.value = value;
        }
    }

    static final class GuardedSerializer extends JsonSerializer<Guarded> {
        @Override
        public void serialize(Guarded guarded, JsonGenerator gen, SerializerProvider provider) throws IOException {
            // Serialized into a buffer first, so a failure halfway through leaves no partial output behind
            TokenBuffer buffer = new TokenBuffer(gen.getCodec(), false);
            try {
                provider.defaultSerializeValue(guarded.value, buffer);
            } catch (Exception | StackOverflowError e) {
                gen.writeString("<omitted unserializable " + guarded.value.getClass().getName() + ">");
                return;
            }
            buffer.serialize(gen);
        }
    }

    static final class CycleGuard extends JsonSerializer<Object> implements ContextualSerializer, ResolvableSerializer {
        private final JsonSerializer<Object> delegate;

        @SuppressWarnings("unchecked")
        CycleGuard(JsonSerializer<?> delegate) {
            this.delegate = (JsonSerializer<Object>) delegate;
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (!enter(provider, value)) {
                gen.writeString(CIRCULAR_REFERENCE_MARKER);
                return;
            }
            try {
                delegate.serialize(value, gen, provider);
            } finally {
                leave(provider, value);
            }
        }

        @Override
        public void serializeWithType(Object value, JsonGenerator gen, SerializerProvider provider, TypeSerializer typeSer) throws IOException {
            if (!enter(provider, value)) {
                gen.writeString(CIRCULAR_REFERENCE_MARKER);
                return;
            }
            try {
                delegate.serializeWithType(value, gen, provider, typeSer);
            } finally {
                leave(provider, value);
            }
        }

        @Override
        public void resolve(SerializerProvider provider) throws JsonMappingException {
            if (delegate instanceof ResolvableSerializer) {
                ((ResolvableSerializer) delegate).resolve(provider);
            }
        }

        @Override
        public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property) throws JsonMappingException {
            if (delegate instanceof ContextualSerializer) {
                JsonSerializer<?> contextual = ((ContextualSerializer) delegate).createContextual(provider, property);
                if (contextual != delegate) {
                    return new CycleGuard(contextual);
                }
            }
            return this;
        }
    }

    private static boolean enter(SerializerProvider provider, Object value) {
        Set<Object> ancestors = ancestors(provider);
        if (ancestors == null) {
            ancestors = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            provider.setAttribute(ANCESTORS, ancestors);
        }
        return ancestors.add(value);
    }

    private static void leave(SerializerProvider provider, Object value) {
        ancestors(provider).remove(value);
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> ancestors(SerializerProvider provider) {
        return (Set<Object>) provider.getAttribute(ANCESTORS);
    }
}
