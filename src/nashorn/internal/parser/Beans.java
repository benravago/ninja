package nashorn.internal.parser;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

import nashorn.internal.ir.ObjectNode;
import nashorn.internal.ir.PropertyNode;

class Beans {
    
    final static Map<String,String> imports = new HashMap<>();
    
    static boolean addImport(String importName, int line) {
        var beanName = importName.substring(importName.lastIndexOf('.') + 1);
        System.out.println(">> addImport "+beanName+' '+importName);
        var previous = "*".equals(beanName) ? null : imports.put(beanName, importName);
        return previous == null;
    }
    
    static String getImport(String shortName) {
        return imports.get(shortName);
    }
    
    static boolean isImported(String name) {
        return imports.containsKey(name);
    }
    
    static boolean addBean(ObjectNode bean) {
        return false; // TODO: register top level bean
    }
    
    static ObjectNode getBean(String id) {
        return null; // TODO: get bean from 'top level' scope 
    }
    
    static void setProperty(List<PropertyNode> elements, long token, int finish, String key, Object value) {
        System.out.println(">> setProperty "+elements+' '+token+' '+finish+' '+key+' '+value);
    }
    
    static void setId(List<PropertyNode> elements, Object bean) {
        // Bean[id] = Bean#0x`hashCode()` if not already set
        System.out.println(">> setId "+elements+' '+bean);
    }
}
