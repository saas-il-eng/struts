/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.struts2.interceptor.parameter;

import com.opensymphony.xwork2.ActionContext;
import com.opensymphony.xwork2.ActionInvocation;
import com.opensymphony.xwork2.TextProvider;
import com.opensymphony.xwork2.inject.Inject;
import com.opensymphony.xwork2.interceptor.MethodFilterInterceptor;
import com.opensymphony.xwork2.interceptor.ValidationAware;
import com.opensymphony.xwork2.security.AcceptedPatternsChecker;
import com.opensymphony.xwork2.security.ExcludedPatternsChecker;
import com.opensymphony.xwork2.util.ClearableValueStack;
import com.opensymphony.xwork2.util.MemberAccessValueStack;
import com.opensymphony.xwork2.util.TextParseUtil;
import com.opensymphony.xwork2.util.ValueStack;
import com.opensymphony.xwork2.util.ValueStackFactory;
import com.opensymphony.xwork2.util.reflection.ReflectionContextState;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.struts2.StrutsConstants;
import org.apache.struts2.action.NoParameters;
import org.apache.struts2.action.ParameterNameAware;
import org.apache.struts2.action.ParameterValueAware;
import org.apache.struts2.dispatcher.HttpParameters;
import org.apache.struts2.dispatcher.Parameter;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.normalizeSpace;

/**
 * This interceptor sets all parameters on the value stack.
 */
public class ParametersInterceptor extends MethodFilterInterceptor {

    private static final Logger LOG = LogManager.getLogger(ParametersInterceptor.class);

    protected static final int PARAM_NAME_MAX_LENGTH = 100;

    private static final Pattern DMI_IGNORED_PATTERN = Pattern.compile("^(action|method):.*", Pattern.CASE_INSENSITIVE);

    private int paramNameMaxLength = PARAM_NAME_MAX_LENGTH;
    private boolean devMode = false;
    private boolean dmiEnabled = false;

    protected boolean ordered = false;
    protected boolean requireAnnotations = false;

    private ValueStackFactory valueStackFactory;
    private ExcludedPatternsChecker excludedPatterns;
    private AcceptedPatternsChecker acceptedPatterns;
    private Set<Pattern> excludedValuePatterns = null;
    private Set<Pattern> acceptedValuePatterns = null;

    @Inject
    public void setValueStackFactory(ValueStackFactory valueStackFactory) {
        this.valueStackFactory = valueStackFactory;
    }

    @Inject(StrutsConstants.STRUTS_DEVMODE)
    public void setDevMode(String mode) {
        this.devMode = BooleanUtils.toBoolean(mode);
    }

    @Inject(value = StrutsConstants.STRUTS_PARAMETERS_REQUIRE_ANNOTATIONS, required = false)
    public void setRequireAnnotations(String requireAnnotations) {
        this.requireAnnotations = BooleanUtils.toBoolean(requireAnnotations);
    }

    @Inject
    public void setExcludedPatterns(ExcludedPatternsChecker excludedPatterns) {
        this.excludedPatterns = excludedPatterns;
    }

    @Inject
    public void setAcceptedPatterns(AcceptedPatternsChecker acceptedPatterns) {
        this.acceptedPatterns = acceptedPatterns;
    }

    @Inject(value = StrutsConstants.STRUTS_ENABLE_DYNAMIC_METHOD_INVOCATION, required = false)
    protected void setDynamicMethodInvocation(String dmiEnabled) {
        this.dmiEnabled = Boolean.parseBoolean(dmiEnabled);
    }

    /**
     * If the param name exceeds the configured maximum length it will not be
     * accepted.
     *
     * @param paramNameMaxLength Maximum length of param names
     */
    public void setParamNameMaxLength(int paramNameMaxLength) {
        this.paramNameMaxLength = paramNameMaxLength;
    }

    static private int countOGNLCharacters(String s) {
        int count = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '.' || c == '[') count++;
        }
        return count;
    }

    /**
     * Compares based on number of '.' and '[' characters (fewer is higher)
     */
    static final Comparator<String> rbCollator = (s1, s2) -> {
        int l1 = countOGNLCharacters(s1);
        int l2 = countOGNLCharacters(s2);
        return l1 < l2 ? -1 : (l2 < l1 ? 1 : s1.compareTo(s2));
    };

    @Override
    public String doIntercept(ActionInvocation invocation) throws Exception {
        Object action = invocation.getAction();
        if (action instanceof NoParameters) {
            return invocation.invoke();
        }

        ActionContext actionContext = invocation.getInvocationContext();
        HttpParameters parameters = retrieveParameters(actionContext);

        if (parameters == null) {
            return invocation.invoke();
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Setting params {}", normalizeSpace(getParameterLogMap(parameters)));
        }

        Map<String, Object> contextMap = actionContext.getContextMap();
        batchApplyReflectionContextState(contextMap, true);
        try {
            setParameters(action, actionContext.getValueStack(), parameters);
        } finally {
            batchApplyReflectionContextState(contextMap, false);
        }

        return invocation.invoke();
    }

    /**
     * Gets the parameter map to apply from wherever appropriate
     *
     * @param actionContext The action context
     * @return The parameter map to apply
     */
    protected HttpParameters retrieveParameters(ActionContext actionContext) {
        return actionContext.getParameters();
    }


    /**
     * Adds the parameters into context's ParameterMap
     * <p>
     * In this class this is a no-op, since the parameters were fetched from the same location. In subclasses both this
     * and {@link #retrieveParameters} should be overridden.
     *
     * @param ac        The action context
     * @param newParams The parameter map to apply
     */
    protected void addParametersToContext(ActionContext ac, Map<String, ?> newParams) {
    }

    /**
     * @deprecated since 6.4.0, use {@link #applyParameters}
     */
    @Deprecated
    protected void setParameters(final Object action, ValueStack stack, HttpParameters parameters) {
        applyParameters(action, stack, parameters);
    }

    protected void applyParameters(final Object action, ValueStack stack, HttpParameters parameters) {
        Map<String, Parameter> acceptableParameters = toAcceptableParameters(parameters, action);

        ValueStack newStack = toNewStack(stack);
        batchApplyReflectionContextState(newStack.getContext(), true);
        applyMemberAccessProperties(newStack);

        applyParametersOnStack(newStack, acceptableParameters, action);

        if (newStack instanceof ClearableValueStack) {
            stack.getActionContext().withConversionErrors(newStack.getActionContext().getConversionErrors());
        }

        addParametersToContext(ActionContext.getContext(), acceptableParameters);
    }

    protected void batchApplyReflectionContextState(Map<String, Object> context, boolean value) {
        ReflectionContextState.setCreatingNullObjects(context, value);
        ReflectionContextState.setDenyMethodExecution(context, value);
        ReflectionContextState.setReportingConversionErrors(context, value);
    }

    protected ValueStack toNewStack(ValueStack stack) {
        ValueStack newStack = valueStackFactory.createValueStack(stack);
        if (newStack instanceof ClearableValueStack) {
            ((ClearableValueStack) newStack).clearContextValues();
            newStack.getActionContext().withLocale(stack.getActionContext().getLocale()).withValueStack(stack);
        }
        return newStack;
    }

    protected void applyMemberAccessProperties(ValueStack stack) {
        if (!(stack instanceof MemberAccessValueStack)) {
            return;
        }
        ((MemberAccessValueStack) stack).useAcceptProperties(acceptedPatterns.getAcceptedPatterns());
        ((MemberAccessValueStack) stack).useExcludeProperties(excludedPatterns.getExcludedPatterns());
    }

    protected Map<String, Parameter> toAcceptableParameters(HttpParameters parameters, Object action) {
        HttpParameters newParams = initNewHttpParameters(parameters);
        Map<String, Parameter> acceptableParameters = initParameterMap();

        for (Map.Entry<String, Parameter> entry : newParams.entrySet()) {
            String parameterName = entry.getKey();
            Parameter parameterValue = entry.getValue();
            if (isAcceptableParameter(parameterName, action) && isAcceptableParameterValue(parameterValue, action)) {
                acceptableParameters.put(parameterName, parameterValue);
            }
        }
        return acceptableParameters;
    }

    protected Map<String, Parameter> initParameterMap() {
        if (ordered) {
            return new TreeMap<>(getOrderedComparator());
        } else {
            return new TreeMap<>();
        }
    }

    protected HttpParameters initNewHttpParameters(HttpParameters parameters) {
        if (ordered) {
            return HttpParameters.create().withComparator(getOrderedComparator()).withParent(parameters).build();
        } else {
            return HttpParameters.create().withParent(parameters).build();
        }
    }

    protected void applyParametersOnStack(ValueStack stack, Map<String, Parameter> parameters, Object action) {
        for (Map.Entry<String, Parameter> entry : parameters.entrySet()) {
            try {
                stack.setParameter(entry.getKey(), entry.getValue().getObject());
            } catch (RuntimeException e) {
                if (devMode) {
                    notifyDeveloperParameterException(action, entry.getKey(), e.getMessage());
                }
            }
        }
    }

    protected void notifyDeveloperParameterException(Object action, String property, String message) {
        String logMsg = "Unexpected Exception caught setting '" + property + "' on '" + action.getClass() + ": " + message;
        if (action instanceof TextProvider) {
            TextProvider tp = (TextProvider) action;
            logMsg = tp.getText("devmode.notification", "Developer Notification:\n{0}", new String[]{logMsg});
        }
        LOG.error(logMsg);

        if (action instanceof ValidationAware) {
            ValidationAware validationAware = (ValidationAware) action;
            Collection<String> messages = validationAware.getActionMessages();
            messages.add(message);
            validationAware.setActionMessages(messages);
        }
    }

    /**
     * Checks if name of parameter can be accepted or thrown away
     *
     * @param name   parameter name
     * @param action current action
     * @return true if parameter is accepted
     */
    protected boolean isAcceptableParameter(String name, Object action) {
        return acceptableName(name) && isAcceptableParameterNameAware(name, action) && isParameterAnnotated(name, action);
    }

    protected boolean isAcceptableParameterNameAware(String name, Object action) {
        return !(action instanceof ParameterNameAware) || ((ParameterNameAware) action).acceptableParameterName(name);
    }

    protected boolean isParameterAnnotated(String name, Object action) {
        if (!requireAnnotations) {
            return true;
        }
        // TODO: Implement
        return true;
    }

    /**
     * Checks if parameter value can be accepted or thrown away
     *
     * @param param  the parameter
     * @param action current action
     * @return true if parameter is accepted
     */
    protected boolean isAcceptableParameterValue(Parameter param, Object action) {
        return isAcceptableParameterValueAware(param, action) && acceptableValue(param.getName(), param.getValue());
    }

    protected boolean isAcceptableParameterValueAware(Parameter param, Object action) {
        return !(action instanceof ParameterValueAware) || ((ParameterValueAware) action).acceptableParameterValue(param.getValue());
    }

    /**
     * Gets an instance of the comparator to use for the ordered sorting.  Override this
     * method to customize the ordering of the parameters as they are set to the
     * action.
     *
     * @return A comparator to sort the parameters
     */
    protected Comparator<String> getOrderedComparator() {
        return rbCollator;
    }

    protected String getParameterLogMap(HttpParameters parameters) {
        if (parameters == null) {
            return "NONE";
        }
        return parameters.entrySet().stream()
                .map(entry -> String.format("%s => %s ", entry.getKey(), entry.getValue().getValue()))
                .collect(joining());
    }

    /**
     * @deprecated since 6.4.0, use {@link #isAcceptableName}
     */
    protected boolean acceptableName(String name) {
        return isAcceptableName(name);
    }

    /**
     * Validates the name passed is:
     * * Within the max length of a parameter name
     * * Is not excluded
     * * Is accepted
     *
     * @param name - Name to check
     * @return true if accepted
     */
    protected boolean isAcceptableName(String name) {
        if (isIgnoredDMI(name)) {
            LOG.trace("DMI is enabled, ignoring DMI method: {}", name);
            return false;
        }
        boolean accepted = isWithinLengthLimit(name) && !isExcluded(name) && isAccepted(name);
        if (devMode && accepted) {
            LOG.debug("Parameter [{}] was accepted and will be appended to action!", name);
        }
        return accepted;
    }

    private boolean isIgnoredDMI(String name) {
        if (!dmiEnabled) {
            return false;
        }
        return DMI_IGNORED_PATTERN.matcher(name).matches();
    }

    /**
     * @deprecated since 6.4.0, use {@link #isAcceptableValue}
     */
    protected boolean acceptableValue(String name, String value) {
        return isAcceptableValue(name, value);
    }

    /**
     * Validates:
     * * Value is null/blank
     * * Value is not excluded
     * * Value is accepted
     *
     * @param name  - Param name (for logging)
     * @param value - value to check
     * @return true if accepted
     */
    protected boolean isAcceptableValue(String name, String value) {
        boolean accepted = value == null || value.isEmpty() || (!isParamValueExcluded(value) && isParamValueAccepted(value));
        if (!accepted) {
            String message = "Value [{}] of parameter [{}] was not accepted and will be dropped!";
            if (devMode) {
                LOG.warn(message, normalizeSpace(value), normalizeSpace(name));
            } else {
                LOG.debug(message, normalizeSpace(value), normalizeSpace(name));
            }
        }
        return accepted;
    }

    protected boolean isWithinLengthLimit(String name) {
        boolean matchLength = name.length() <= paramNameMaxLength;
        if (!matchLength) {
            if (devMode) {
                LOG.warn("Parameter [{}] is too long, allowed length is [{}]. Use Interceptor Parameter Overriding " +
                        "to override the limit, see more at\n" +
                        "https://struts.apache.org/core-developers/interceptors.html#interceptor-parameter-overriding",
                    name, paramNameMaxLength);
            } else {
                LOG.warn("Parameter [{}] is too long, allowed length is [{}]", name, paramNameMaxLength);
            }
        }
        return matchLength;
    }

    protected boolean isAccepted(String paramName) {
        AcceptedPatternsChecker.IsAccepted result = acceptedPatterns.isAccepted(paramName);
        if (!result.isAccepted()) {
            if (devMode) {
                LOG.warn("Parameter [{}] didn't match accepted pattern [{}]! See Accepted / Excluded patterns at\n" +
                                "https://struts.apache.org/security/#accepted--excluded-patterns",
                        paramName, result.getAcceptedPattern());
            } else {
                LOG.debug("Parameter [{}] didn't match accepted pattern [{}]!", paramName, result.getAcceptedPattern());
            }
            return false;
        }
        return true;
    }

    protected boolean isExcluded(String paramName) {
        ExcludedPatternsChecker.IsExcluded result = excludedPatterns.isExcluded(paramName);
        if (result.isExcluded()) {
            if (devMode) {
                LOG.warn("Parameter [{}] matches excluded pattern [{}]! See Accepted / Excluded patterns at\n" +
                        "https://struts.apache.org/security/#accepted--excluded-patterns",
                    paramName, result.getExcludedPattern());
            } else {
                LOG.debug("Parameter [{}] matches excluded pattern [{}]!", paramName, result.getExcludedPattern());
            }
            return true;
        }
        return false;
    }

    protected boolean isParamValueExcluded(String value) {
        if (!hasParamValuesToExclude()) {
            LOG.debug("'excludedValuePatterns' not defined so anything is allowed");
            return false;
        }
        for (Pattern excludedValuePattern : excludedValuePatterns) {
            if (excludedValuePattern.matcher(value).matches()) {
                if (devMode) {
                    LOG.warn("Parameter value [{}] matches excluded pattern [{}]! See Accepting/Excluding parameter values at\n" +
                            "https://struts.apache.org/core-developers/parameters-interceptor#excluding-parameter-values",
                        value, excludedValuePatterns);
                } else {
                    LOG.debug("Parameter value [{}] matches excluded pattern [{}]", value, excludedValuePattern);
                }
                return true;
            }
        }
        return false;
    }

    protected boolean isParamValueAccepted(String value) {
        if (!hasParamValuesToAccept()) {
            LOG.debug("'acceptedValuePatterns' not defined so anything is allowed");
            return true;
        }
        for (Pattern acceptedValuePattern : acceptedValuePatterns) {
            if (acceptedValuePattern.matcher(value).matches()) {
                return true;
            }
        }
        if (devMode) {
            LOG.warn("Parameter value [{}] didn't match accepted pattern [{}]! See Accepting/Excluding parameter values at\n" +
                    "https://struts.apache.org/core-developers/parameters-interceptor#excluding-parameter-values",
                value, acceptedValuePatterns);
        } else {
            LOG.debug("Parameter value [{}] was not accepted!", value);
        }
        return false;
    }

    private boolean hasParamValuesToExclude() {
        return excludedValuePatterns != null && !excludedValuePatterns.isEmpty();
    }

    private boolean hasParamValuesToAccept() {
        return acceptedValuePatterns != null && !acceptedValuePatterns.isEmpty();
    }

    /**
     * Whether to order the parameters or not
     *
     * @return True to order
     */
    public boolean isOrdered() {
        return ordered;
    }

    /**
     * Set whether to order the parameters by object depth or not
     *
     * @param ordered True to order them
     */
    public void setOrdered(boolean ordered) {
        this.ordered = ordered;
    }

    /**
     * Sets a comma-delimited list of regular expressions to match
     * parameters that are allowed in the parameter map (aka whitelist).
     * <p>
     * Don't change the default unless you know what you are doing in terms
     * of security implications.
     * </p>
     *
     * @param commaDelim A comma-delimited list of regular expressions
     */
    public void setAcceptParamNames(String commaDelim) {
        acceptedPatterns.setAcceptedPatterns(commaDelim);
    }

    /**
     * Sets a comma-delimited list of regular expressions to match
     * parameters that should be removed from the parameter map.
     *
     * @param commaDelim A comma-delimited list of regular expressions
     */
    public void setExcludeParams(String commaDelim) {
        excludedPatterns.setExcludedPatterns(commaDelim);
    }

    /**
     * Sets a comma-delimited list of regular expressions to match
     * values of parameters that should be accepted and included in the parameter map.
     *
     * @param commaDelimitedPatterns A comma-delimited set of regular expressions
     */
    public void setAcceptedValuePatterns(String commaDelimitedPatterns) {
        Set<String> patterns = TextParseUtil.commaDelimitedStringToSet(commaDelimitedPatterns);
        if (acceptedValuePatterns == null) {
            // Limit unwanted log entries (for 1st call, acceptedValuePatterns null)
            LOG.debug("Sets accepted value patterns to [{}], note this may impact the safety of your application!", patterns);
        } else {
            LOG.warn("Replacing accepted patterns [{}] with [{}], be aware that this may impact safety of your application!",
                acceptedValuePatterns, patterns);
        }
        acceptedValuePatterns = new HashSet<>(patterns.size());
        try {
            for (String pattern : patterns) {
                acceptedValuePatterns.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
            }
        } finally {
            acceptedValuePatterns = unmodifiableSet(acceptedValuePatterns);
        }
    }

    /**
     * Sets a comma-delimited list of regular expressions to match
     * values of parameters that should be removed from the parameter map.
     *
     * @param commaDelimitedPatterns A comma-delimited set of regular expressions
     */
    public void setExcludedValuePatterns(String commaDelimitedPatterns) {
        Set<String> patterns = TextParseUtil.commaDelimitedStringToSet(commaDelimitedPatterns);
        if (excludedValuePatterns == null) {
            // Limit unwanted log entries (for 1st call, excludedValuePatterns null)
            LOG.debug("Setting excluded value patterns to [{}]", patterns);
        } else {
            LOG.warn("Replacing excluded value patterns [{}] with [{}], be aware that this may impact safety of your application!",
                excludedValuePatterns, patterns);
        }
        excludedValuePatterns = new HashSet<>(patterns.size());
        try {
            for (String pattern : patterns) {
                excludedValuePatterns.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
            }
        } finally {
            excludedValuePatterns = unmodifiableSet(excludedValuePatterns);
        }
    }
}
