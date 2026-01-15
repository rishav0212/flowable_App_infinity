package com.example.flowable_app.config;

import com.example.flowable_app.service.FlowableDataService;
import com.example.flowable_app.service.FlowableMapService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.*;
import org.flowable.validation.validator.ProcessLevelValidator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 🟢 PROCESS DEPLOYMENT VALIDATOR (GENERIC VERSION)
 * 1. Supports generic service registration via reflection.
 * 2. Validates Argument COUNTS and TYPES against Java signatures.
 * 3. Scans recursively (Loops, Listeners, JSON).
 * 4. No hardcoded rules for specific methods.
 */
@Component
public class ProcessDeploymentValidator extends ProcessLevelValidator {

    private static final Pattern EXPRESSION_CALL = Pattern.compile("(data|map)\\.([a-zA-Z0-9_]+)\\s*\\(");

    private static final Set<String> IGNORED_FIELDS = new HashSet<>(Arrays.asList(
            "id", "targetRef", "sourceRef", "defaultFlow",
            "xmlRowNumber", "xmlColumnNumber", "di", "incomingFlows", "outgoingFlows"
    ));

    // Key = "prefix.methodName" (e.g. "data.selectVal")
    private final Map<String, List<Class<?>[]>> methodSignatures = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProcessDeploymentValidator() {
        registerServiceMethods("data", FlowableDataService.class);
        registerServiceMethods("map", FlowableMapService.class);
    }

    private void registerServiceMethods(String prefix, Class<?> serviceClass) {
        for (Method m : serviceClass.getMethods()) {
            String key = prefix + "." + m.getName();
            methodSignatures.computeIfAbsent(key, k -> new ArrayList<>()).add(m.getParameterTypes());
        }
    }

    @Override
    protected void executeValidation(BpmnModel bpmnModel, Process process, List<org.flowable.validation.ValidationError> errors) {
        scanFlowElements(process.getFlowElements(), errors);
    }

    private void scanFlowElements(Collection<FlowElement> elements, List<org.flowable.validation.ValidationError> errors) {
        for (FlowElement element : elements) {
            validateElement(element, errors);
            if (element instanceof FlowElementsContainer) {
                scanFlowElements(((FlowElementsContainer) element).getFlowElements(), errors);
            }
        }
    }

    private void validateElement(FlowElement element, List<org.flowable.validation.ValidationError> errors) {
        String
                context =
                element.getClass().getSimpleName() +
                        " '" +
                        (element.getName() != null ? element.getName() : element.getId()) +
                        "'";

        // 1. SERVICE TASK: Check Java Class
        if (element instanceof ServiceTask) {
            ServiceTask st = (ServiceTask) element;
            if (ImplementationType.IMPLEMENTATION_TYPE_CLASS.equals(st.getImplementationType())) {
                try {
                    Class.forName(st.getImplementation());
                } catch (ClassNotFoundException e) {
                    addError(errors, "Service Task uses unknown Java class: '" + st.getImplementation() + "'", element);
                }
            }
        }

        // 2. UNIVERSAL SCAN
        scanBeanProperties(element, context, errors, element);

        // 3. LOOP & LISTENER SCAN
        if (element instanceof Activity) {
            MultiInstanceLoopCharacteristics loop = ((Activity) element).getLoopCharacteristics();
            if (loop != null) scanBeanProperties(loop, context + " (Loop)", errors, element);
        }
        if (element instanceof HasExecutionListeners) {
            ((HasExecutionListeners) element).getExecutionListeners().stream()
                    .filter(l -> "expression".equals(l.getImplementationType()))
                    .forEach(l -> checkExpr(l.getImplementation(), context + " Listener", errors, element));
        }
        if (element instanceof UserTask) {
            ((UserTask) element).getTaskListeners().stream()
                    .filter(l -> "expression".equals(l.getImplementationType()))
                    .forEach(l -> checkExpr(l.getImplementation(), context + " TaskListener", errors, element));

            // JSON Check
            List<ExtensionElement>
                    props =
                    ((UserTask) element).getExtensionElements().getOrDefault("property", Collections.emptyList());
            for (ExtensionElement prop : props) {
                if ("externalActions".equals(prop.getAttributeValue(null, "name"))) {
                    String json = prop.getElementText();
                    if (json != null) {
                        String clean = json.trim().replace("<![CDATA[", "").replace("]]>", "");
                        try {
                            objectMapper.readTree(clean);
                        } catch (Exception e) {
                            addError(errors, "Invalid JSON in externalActions. " + e.getMessage(), element);
                        }
                        checkExpr(clean, context + " externalActions", errors, element);
                    }
                }
            }
        }
    }

    private void scanBeanProperties(Object bean, String context, List<org.flowable.validation.ValidationError> errors, FlowElement sourceElement) {
        try {
            BeanInfo beanInfo = Introspector.getBeanInfo(bean.getClass());
            for (PropertyDescriptor pd : beanInfo.getPropertyDescriptors()) {
                if (String.class.equals(pd.getPropertyType()) && !IGNORED_FIELDS.contains(pd.getName())) {
                    Method getter = pd.getReadMethod();
                    if (getter != null) {
                        String value = (String) getter.invoke(bean);
                        checkExpr(value, context + " field '" + pd.getName() + "'", errors, sourceElement);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore reflection errors
        }
    }

    // 🟢 CORE VALIDATION LOGIC
    private void checkExpr(String text, String fieldName, List<org.flowable.validation.ValidationError> errors, FlowElement element) {
        if (!StringUtils.hasText(text)) return;
        Matcher m = EXPRESSION_CALL.matcher(text);

        while (m.find()) {
            String prefix = m.group(1);
            String method = m.group(2);
            String fullKey = prefix + "." + method;

            if (!methodSignatures.containsKey(fullKey)) {
                addError(errors, "Unknown function '" + fullKey + "' in " + fieldName, element);
                continue;
            }

            // Extract arguments string
            String argsStr = extractArgsString(text, m.end());
            List<String> argList = splitArgs(argsStr);
            int argCount = argList.size();

            // Find a matching signature (Count AND Types)
            boolean validSignatureFound = false;
            StringBuilder typeMismatchMsg = new StringBuilder();

            for (Class<?>[] expectedTypes : methodSignatures.get(fullKey)) {
                // 1. Check Count
                if (expectedTypes.length != argCount) continue;

                // 2. Check Types
                boolean typesMatch = true;
                for (int i = 0; i < argCount; i++) {
                    if (!isTypeCompatible(argList.get(i), expectedTypes[i])) {
                        typesMatch = false;
                        typeMismatchMsg.append(String.format("Arg %d: '%s' is not compatible with %s. ",
                                i + 1, argList.get(i), expectedTypes[i].getSimpleName()));
                        break;
                    }
                }

                if (typesMatch) {
                    validSignatureFound = true;
                    break;
                }
            }

            if (!validSignatureFound) {
                if (typeMismatchMsg.length() > 0) {
                    // We found correct count but wrong types
                    addError(errors,
                            "Type Mismatch in '" + fullKey + "': " + typeMismatchMsg.toString().trim(),
                            element);
                } else {
                    // Wrong count
                    addError(errors,
                            "Function '" +
                                    fullKey +
                                    "' called with " +
                                    argCount +
                                    " arguments. No matching signature found.",
                            element);
                }
            }
        }
    }

    // 🟢 TYPE CHECKER
    private boolean isTypeCompatible(String argValue, Class<?> expectedType) {
        argValue = argValue.trim();

        // 1. Variable / Expression (${...}) -> Assume VALID (Dynamic)
        if (argValue.startsWith("${") ||
                argValue.startsWith("#{") ||
                argValue.contains("data.") ||
                argValue.contains("map.")) {
            return true;
        }

        // 2. String Literal -> Check if expected type is String or Object
        if (argValue.startsWith("\"") || argValue.startsWith("'")) {
            return String.class.isAssignableFrom(expectedType) || Object.class.equals(expectedType);
        }

        // 3. Boolean Literal -> Check boolean
        if ("true".equalsIgnoreCase(argValue) || "false".equalsIgnoreCase(argValue)) {
            return boolean.class.equals(expectedType) ||
                    Boolean.class.equals(expectedType) ||
                    Object.class.equals(expectedType);
        }

        // 4. Number Literal -> Check Number types
        if (argValue.matches("-?\\d+(\\.\\d+)?")) {
            return Number.class.isAssignableFrom(expectedType) ||
                    int.class.equals(expectedType) || long.class.equals(expectedType) ||
                    double.class.equals(expectedType) || Object.class.equals(expectedType);
        }

        // 5. Map Literal (JUEL style: {'k':'v'}) -> Check if expected type is Map
        if (argValue.startsWith("{") && argValue.endsWith("}")) {
            return Map.class.isAssignableFrom(expectedType) || Object.class.equals(expectedType);
        }

        // 6. Null
        if ("null".equals(argValue)) return true;

        // Default: If it's a raw variable name (e.g. myVar), assume it's valid
        return true;
    }

    private String extractArgsString(String text, int start) {
        int depth = 1, i = start;
        boolean inQuote = false;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i < text.length() && text.charAt(i) == ')') return "";

        int begin = i;
        for (; i < text.length() && depth > 0; i++) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') inQuote = !inQuote;
            else if (!inQuote) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
            }
        }
        return text.substring(begin, i - 1);
    }

    private List<String> splitArgs(String argsStr) {
        List<String> args = new ArrayList<>();
        if (argsStr.isEmpty()) return args;

        int depth = 0;
        boolean inQuote = false;
        StringBuilder current = new StringBuilder();

        for (char c : argsStr.toCharArray()) {
            if (c == '\'' || c == '"') inQuote = !inQuote;
            if (!inQuote) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
            }

            if (c == ',' && depth == 0 && !inQuote) {
                args.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        args.add(current.toString().trim());
        return args;
    }

    private void addError(List<org.flowable.validation.ValidationError> errors, String msg, FlowElement element) {
        org.flowable.validation.ValidationError error = new org.flowable.validation.ValidationError();
        error.setProblem(msg);
        error.setActivityId(element.getId());
        error.setActivityName(element.getName());
        error.setValidatorSetName("Process Deployment Validator");
        error.setXmlLineNumber(element.getXmlRowNumber());
        errors.add(error);
    }
}