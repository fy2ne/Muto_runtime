package me.fy2ne.muto.engine;

import java.util.HashMap;
import java.util.Map;

public class DynamicBytecodeLoader extends ClassLoader {
    private final Map<String, byte[]> definitions = new HashMap<>();

    public DynamicBytecodeLoader(ClassLoader parent) {
        super(parent);
    }

    public void register(String name, byte[] bytes) {
        definitions.put(name, bytes);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = definitions.get(name);
        if (bytes != null) {
            return defineClass(name, bytes, 0, bytes.length);
        }
        return super.findClass(name);
    }
}
