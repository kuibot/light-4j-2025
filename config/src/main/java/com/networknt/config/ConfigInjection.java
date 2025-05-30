/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.config;

import com.networknt.config.yml.DecryptConstructor;
import com.networknt.decrypt.Decryptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class has a public method called getInjectValue which is used to generate
 * the values which need to be injected from environment variables or a specific
 * file called "values.yaml".
 * <p>
 * Three injection order defined as following and default mode is [2]:
 * [0] Inject from "values.yaml" only.
 * [1] Inject from system environment first, then overwrite by "values.yaml"
 * if exist.
 * [2] Inject from "values.yaml" first, then overwrite with the values in the
 * system environment.
 * This parameter can be set through setting system property "injection_order" in
 * commend line
 * <p>
 * Created by jiachen on 2019-01-08.
 */
public class ConfigInjection {
    // Define the injection order
    private static final String INJECTION_ORDER = "injection_order";
    private static final String INJECTION_ORDER_CODE = (!System.getProperty(INJECTION_ORDER, "").equals("")) ?
            System.getProperty(INJECTION_ORDER, "") : "2";

    // Define one of the injection value source "values.yaml" and list of exclusion config files
    public static final String CENTRALIZED_MANAGEMENT = "values";
    private static final String SCALABLE_CONFIG = "config";
    private static final String EXCLUSION_CONFIG_FILE_LIST = "exclusionConfigFileList";
    private static final String ALLOW_DEFAULT_EMPTY = "allowDefaultValueEmpty";
    private static final String CONVERT_ENV_VARS = "convertEnvVars";

    private static final Map<String, Object> exclusionMap = Config.getInstance().getJsonMapConfig(SCALABLE_CONFIG);

    // Define the injection pattern which represents the injection points
    private static final Pattern pattern = Pattern.compile("\\$\\{(.*?)\\}");

    private static final String[] trueArray = {"y", "Y", "yes", "Yes", "YES", "true", "True", "TRUE", "on", "On", "ON"};
    private static final String[] falseArray = {"n", "N", "no", "No", "NO", "false", "False", "FALSE", "off", "Off", "OFF"};
    private static final Decryptor decryptor = DecryptConstructor.getInstance().getDecryptor();

    public static Map<String, Object> decryptedValueMap = Config.getInstance().getDefaultJsonMapConfigNoCache(CENTRALIZED_MANAGEMENT);
    public static Map<String, Object> undecryptedValueMap = Config.getNoneDecryptedInstance().getDefaultJsonMapConfigNoCache(CENTRALIZED_MANAGEMENT);


    // Method used to generate the values from environment variables or "values.yaml"
    public static Object getInjectValue(String string, boolean decrypt) {
        Matcher m = pattern.matcher(string);
        StringBuffer sb = new StringBuffer();
        // Parse the content inside pattern "${}" when this pattern is found
        while (m.find()) {
            // Get parsing result
            Object value = getValue(m.group(1), decrypt);
            // Return directly when the parsing result don't need to be cast to String
            if (!(value instanceof String)) {
                return value;
            }
            String valueStr = (String)value;
            if(valueStr.contains("\\$")) {
                m.appendReplacement(sb, (String)value);
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement((String)value));
            }
        }
        return m.appendTail(sb).toString();
    }

    // Return the list of exclusion files list which includes the names of config files that shouldn't be injected
    // Double check values and exclusions to ensure no dead loop
    public static boolean isExclusionConfigFile(String configName) {
        List<Object> exclusionConfigFileList = (exclusionMap == null || exclusionMap.get(EXCLUSION_CONFIG_FILE_LIST) == null) ? new ArrayList<>() : (List<Object>) exclusionMap.get(EXCLUSION_CONFIG_FILE_LIST);
        return SCALABLE_CONFIG.equals(configName)
                || exclusionConfigFileList.contains(configName);
    }

    public static Decryptor getDecryptor() {
        return decryptor;
    }

    static String convertEnvVars(String input){
        // check for any non-alphanumeric chars and convert to underscore
        // convert to upper case
        if (input == null) {
            return null;
        }
        if (exclusionMap.get(CONVERT_ENV_VARS) != null && !(Boolean)exclusionMap.get(CONVERT_ENV_VARS)) {
            // only if the flag is set to false, then don't convert.
            return input;
        }
        return input.replaceAll("[^A-Za-z0-9]", "_").toUpperCase();
    }

    public static Object decryptEnvValue(Decryptor decryptor, String envVal) {
        Object decryptedEnvValue;
        //checking if the value put in env is encrypted. If yes then decrypting it.
        if (envVal != null && envVal.trim().startsWith(Decryptor.CRYPT_PREFIX)) {
            decryptedEnvValue = typeCast(decryptor.decrypt(envVal));
        }else
            decryptedEnvValue = envVal;
        return decryptedEnvValue;
    }


    // Method used to parse the content inside pattern "${}"
    private static Object getValue(String content, boolean decrypt) {
        InjectionPattern injectionPattern = getInjectionPattern(content);
        Object value = null;
        if (injectionPattern != null) {
            // Flag to validate whether the environment or values.yml contains the corresponding field
            boolean containsField = false;
            // Use key of injectionPattern to get value from both environment variables and "values.yaml"
            String envValString = System.getenv(convertEnvVars(injectionPattern.getKey()));

            Object envValue;
            Object fileValue;
            if(decrypt) {
                envValue = decryptEnvValue(decryptor, envValString);
                fileValue = (decryptedValueMap != null) ? decryptedValueMap.get(injectionPattern.getKey()) : null;
                // Skip none validation to inject null or empty string directly when the corresponding field is presented in value.yml or environment
                if ((decryptedValueMap != null && decryptedValueMap.containsKey(injectionPattern.getKey())) ||
                        (System.getenv() != null && System.getenv().containsKey(injectionPattern.getKey()))) {
                    containsField = true;
                }
            } else {
                envValue = envValString;
                fileValue = (undecryptedValueMap != null) ? undecryptedValueMap.get(injectionPattern.getKey()) : null;
                // Skip none validation to inject null or empty string directly when the corresponding field is presented in value.yml or environment
                if ((undecryptedValueMap != null && undecryptedValueMap.containsKey(injectionPattern.getKey())) ||
                        (System.getenv() != null && System.getenv().containsKey(injectionPattern.getKey()))) {
                    containsField = true;
                }
            }
            // Return different value from different sources based on injection order defined before
            if ((INJECTION_ORDER_CODE.equals("2") && envValue != null) || (INJECTION_ORDER_CODE.equals("1") && fileValue == null)) {
                value = envValue;
            } else {
                value = fileValue;
            }
            // Return default value when no matched value found from environment variables and "values.yaml"
            if (value == null && !containsField) {
                value = typeCast(injectionPattern.getDefaultValue());
                // Throw exception when error text provided
                if (value == null || value.equals("")) {
                    String error_text = injectionPattern.getErrorText();
                    if (error_text != null && !error_text.isEmpty()) {
                        throw new ConfigException(error_text);
                    }
                    // Throw exception when no parsing result found
                    if (exclusionMap.get(ALLOW_DEFAULT_EMPTY)== null || !(Boolean)exclusionMap.get(ALLOW_DEFAULT_EMPTY)) {
                        throw new ConfigException("\"${" + content + "}\" appears in config file cannot be expanded");
                    }
                }
            }
        }
        return value;
    }

    // Get instance of InjectionPattern based on the contents inside pattern "${}"
    private static InjectionPattern getInjectionPattern(String contents) {
        if (contents == null || contents.trim().isEmpty()) {
            return null;
        }
        InjectionPattern injectionPattern = new InjectionPattern();
        contents = contents.trim();
        // Retrieve key, default value and error text
        String[] array = contents.split(":", 2);
        array[0] = array[0].trim();
        if (array[0].isEmpty()) {
            return null;
        }
        // Set key of the injectionPattern
        injectionPattern.setKey(array[0]);
        if (array.length == 2) {
            // Adding space after colon is enabled, so trim is needed
            array[1] = array[1].trim();
            // Set error text
            if (array[1].startsWith("?")) {
                injectionPattern.setErrorText(array[1].substring(1));
            } else if (array[1].startsWith("$")) {
                // Skip this injection when "$" is only character found after the ":"
                if (array[1].length() == 1) {
                    injectionPattern.setDefaultValue("\\$\\{" + array[0] + "\\}");
                    // Otherwise, treat as a default value
                    // Add "\\" since $ is a special character
                } else {
                    injectionPattern.setDefaultValue("\\" + array[1]);
                }
                // Set default value
            } else {
                injectionPattern.setDefaultValue(array[1]);
            }
        }
        return injectionPattern;
    }

    /**
     * Wrap the contents inside the pattern ${} into a private class which contains
     * three fields: key, defaultValue and errorText
     */
    private static class InjectionPattern {
        private String key;
        private String defaultValue;
        private String errorText;

        public String getErrorText() {
            return errorText;
        }

        public void setErrorText(String errorTest) {
            this.errorText = errorTest;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    // Method used to cast string into int, double or boolean
    private static Object typeCast(String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        // Try to cast to boolean true
        for (String trueString : trueArray) {
            if (trueString.equals(str)) {
                return true;
            }
        }
        // Try to cast to boolean false
        for (String falseString : falseArray) {
            if (falseString.equals(str)) {
                return false;
            }
        }
        // Strings that cannot cast to int or double are treated as string
        try {
            return Integer.parseInt(str);
        } catch (Exception e1) {
            try {
                return Double.parseDouble(str);
            } catch (Exception e2) {
                return str;
            }
        }
    }
}
