package nashorn.internal.parser;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

import nashorn.internal.ir.Expression;
import nashorn.internal.ir.IdentNode;
import nashorn.internal.ir.LiteralNode;
import nashorn.internal.ir.ObjectNode;
import nashorn.internal.ir.PropertyNode;

class Beans {

    final static Map<String,String> imports = new HashMap<>();

    // TODO: put Bean's and import's in a global var
    // see Parser#variableStatement:1395
    //     Parser#variableDeclarationList:1442
    //     Parser#appendStatement:5045

    // TODO:
    // look at how Parser implements 'class' and nashorn.internal.ir.ClassNode
    // maybe reuse this for Bean declaration

    static boolean addImport(String importName, Object context) {
        var beanName = importName.substring(importName.lastIndexOf('.') + 1);
        System.out.println(">> addImport "+beanName+' '+importName+' '+context);
        var previous = "*".equals(beanName) ? null : imports.put(beanName, importName);
        return previous == null;
    }

    static String getImport(String shortName) {
        return imports.get(shortName);
    }

    static boolean isImported(String name) {
        return imports.containsKey(name);
    }

    static boolean addBean(ObjectNode bean, Object context) {
        System.out.println(">> addBean "+bean+' '+context);
        return false; // TODO: register top level bean
    }

    static ObjectNode getBean(String id) {
        return null; // TODO: get bean from 'top level' scope
    }

    static ObjectNode setInfo(ObjectNode bean, List<PropertyNode> elements, String type, LiteralNode<Expression[]> arguments) {
        if (type != null) {
            setProperty(elements,"$bean",type);
        }
        if (arguments != null) {
            setProperty(elements,"$arguments",arguments);
        }
        setId(bean,elements);
        return bean;
    }

    static void setProperty(List<PropertyNode> elements, String key, Object value) {
        // System.out.println(">> setProperty "+elements+' '+key+' '+value);
        var i = elements.iterator();
        while (i.hasNext()) {
            if (hasKey(i.next(),key)) {
                i.remove();
                break;
            }
        }
        elements.add(property(key,value));
    }

    static void setId(Object bean, List<PropertyNode> elements) {
        // System.out.println(">> setId "+elements+' '+bean);
        for (var pn:elements) {
            if (hasKey(pn,"id")) return;
        }
        elements.add(property("id", "0x"+Integer.toHexString(bean.hashCode()) ));
    }

    static boolean hasKey(PropertyNode node, String key) {
        return key.equals(node.getKey().toString(false));
    }

    static PropertyNode property(String key, Object value) {
        return new PropertyNode(0, 0, ident(key), expression(value), null, null, false, true);
    }

    static Expression expression(Object value) {
        return value instanceof String ? ident((String)value) : (Expression)value;
    }

    static IdentNode ident(String name) {
        return new IdentNode(0,0,name).setIsPropertyName();
    }

}
