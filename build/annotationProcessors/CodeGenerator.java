/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.annotationProcessors;

import org.mozilla.gecko.annotationProcessors.utils.Utils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;

public class CodeGenerator {
    // Buffers holding the strings to ultimately be written to the output files.
    private final StringBuilder wrapperStartupCode = new StringBuilder();
    private final StringBuilder wrapperMethodBodies = new StringBuilder();
    private final StringBuilder headerFields = new StringBuilder();
    private final StringBuilder headerMethods = new StringBuilder();

    private final HashSet<String> seenClasses = new HashSet<String>();

    private final String GENERATED_COMMENT = "// GENERATED CODE\n" +
            "// Generated by the Java program at /build/annotationProcessors at compile time from\n" +
            "// annotations on Java methods. To update, change the annotations on the corresponding Java\n" +
            "// methods and rerun the build. Manually updating this file will cause your build to fail.\n\n";

    public CodeGenerator() {
        // Write the file header things. Includes and so forth.
        // GeneratedJNIWrappers.cpp is generated as the concatenation of wrapperStartupCode with
        // wrapperMethodBodies. Similarly, GeneratedJNIWrappers.h is the concatenation of headerFields
        // with headerMethods.
        wrapperStartupCode.append(GENERATED_COMMENT);
        wrapperStartupCode.append(
                "#include \"nsXPCOMStrings.h\"\n" +
                "#include \"AndroidBridge.h\"\n" +
                "#include \"AndroidBridgeUtilities.h\"\n" +
                "\n" +
                "#ifdef DEBUG\n" +
                "#define ALOG_BRIDGE(args...) ALOG(args)\n" +
                "#else\n" +
                "#define ALOG_BRIDGE(args...) ((void)0)\n" +
                "#endif\n" +
                "\n" +
                "using namespace mozilla;\n" +
                "void AndroidBridge::InitStubs(JNIEnv *jEnv) {\n" +
                "    initInit();\n");
        // Now we write the various GetStaticMethodID calls here...

        headerFields.append("protected:\n\n");
        headerMethods.append(GENERATED_COMMENT);
        headerMethods.append("public:\n\n");
    }

    /**
     * Append the appropriate generated code to the buffers for the method provided.
     *
     * @param aMethodTuple The Java method, plus the name for the generated method.
     * @param aClass       The class to which the Java method belongs.
     */
    public void generateMethod(MethodWithAnnotationInfo aMethodTuple, Class<?> aClass) {
        // Unpack the tuple and extract some useful fields from the Method..
        Method aMethod = aMethodTuple.method;
        String CMethodName = aMethodTuple.wrapperName;

        String javaMethodName = aMethod.getName();

        ensureClassHeaderAndStartup(aClass);

        writeHeaderField(CMethodName);
        writeStartupCode(CMethodName, javaMethodName, aMethod, aClass);

        // Get the C++ method signature for this method.
        String implementationSignature = Utils.getCImplementationMethodSignature(aMethod, CMethodName);
        String headerSignature = Utils.getCHeaderMethodSignature(aMethod, CMethodName, aMethodTuple.isStatic);

        // Add the header signature to the header file.
        headerMethods.append(headerSignature);
        headerMethods.append(";\n");

        // Use the implementation signature to generate the method body...
        writeMethodBody(implementationSignature, CMethodName, aMethod, aClass, aMethodTuple.isStatic, aMethodTuple.isMultithreaded);
    }

    /**
     * Writes the appropriate header and startup code to ensure the existence of a reference to the
     * class specified. If this is already done, does nothing.
     *
     * @param aClass The target class.
     */
    private void ensureClassHeaderAndStartup(Class<?> aClass) {
        String className = aClass.getCanonicalName();
        if (seenClasses.contains(className)) {
            return;
        }

        // Add a field to hold the reference...
        headerFields.append("\njclass ");
        headerFields.append(Utils.getClassReferenceName(aClass));
        headerFields.append(";\n");

        // Add startup code to populate it..
        wrapperStartupCode.append('\n');
        wrapperStartupCode.append(Utils.getStartupLineForClass(aClass));

        seenClasses.add(className);
    }

    /**
     * Generates the method body of the C++ wrapper function for the Java method indicated.
     *
     * @param methodSignature The previously-generated C++ method signature for the method to be
     *                        generated.
     * @param aCMethodName    The C++ method name for the method to be generated.
     * @param aMethod         The Java method to be wrapped by the C++ method being generated.
     * @param aClass          The Java class to which the method belongs.
     */
    private void writeMethodBody(String methodSignature, String aCMethodName, Method aMethod, Class<?> aClass, boolean aIsStaticBridgeMethod, boolean aIsMultithreaded) {
        Class<?>[] argumentTypes = aMethod.getParameterTypes();
        Class<?> returnType = aMethod.getReturnType();

        // The start-of-function boilerplate. Does the bridge exist? Does the env exist? etc.
        wrapperMethodBodies.append('\n');
        wrapperMethodBodies.append(methodSignature);

        wrapperMethodBodies.append(" {\n");

        // Static stubs check the bridge instance has been created before trying to run.
        if (aIsStaticBridgeMethod) {
            wrapperMethodBodies.append("    if (!sBridge) {\n" +
                                       "        ALOG_BRIDGE(\"Aborted: No sBridge - %s\", __PRETTY_FUNCTION__);\n" +
                                       "        return").append(Utils.getFailureReturnForType(returnType)).append(";\n" +
                                       "    }\n\n");
        }
        wrapperMethodBodies.append("    JNIEnv *env = ");
        if (!aIsMultithreaded) {
            wrapperMethodBodies.append("GetJNIEnv();\n");
        } else {
            wrapperMethodBodies.append("GetJNIForThread();\n");
        }
        wrapperMethodBodies.append("    if (!env) {\n" +
                                   "        ALOG_BRIDGE(\"Aborted: No env - %s\", __PRETTY_FUNCTION__);\n" +
                                   "        return").append(Utils.getFailureReturnForType(returnType)).append(";\n" +
                                   "    }\n\n");

        boolean isObjectReturningMethod = !returnType.getCanonicalName().equals("void") && Utils.doesReturnObjectType(aMethod);

        // Determine the number of local refs required for our local frame..
        // AutoLocalJNIFrame is not applicable here due to it's inability to handle return values.
        int localReferencesNeeded = Utils.enumerateReferenceArguments(aMethod);
        if (isObjectReturningMethod) {
            localReferencesNeeded++;
        }
        wrapperMethodBodies.append("    if (env->PushLocalFrame(").append(localReferencesNeeded).append(") != 0) {\n" +
                                   "        ALOG_BRIDGE(\"Exceptional exit of: %s\", __PRETTY_FUNCTION__);\n" +
                                   "        env->ExceptionDescribe();\n"+
                                   "        env->ExceptionClear();\n" +
                                   "        return").append(Utils.getFailureReturnForType(returnType)).append(";\n" +
                                   "    }\n\n");

        // Marshall arguments, if we have any.
        boolean hasArguments = argumentTypes.length != 0;

        // We buffer the arguments to the call separately to avoid needing to repeatedly iterate the
        // argument list while building this line. In the coming code block, we simultaneously
        // construct any argument marshalling code (Creation of jstrings, placement of arguments
        // into an argument array, etc. and the actual argument list passed to the function (in
        // argumentContent).
        StringBuilder argumentContent = new StringBuilder();
        if (hasArguments) {
            argumentContent.append(", ");
            // If we have >2 arguments, use the jvalue[] calling approach.
            if (argumentTypes.length > 2) {
                wrapperMethodBodies.append("    jvalue args[").append(argumentTypes.length).append("];\n");
                for (int aT = 0; aT < argumentTypes.length; aT++) {
                    wrapperMethodBodies.append("    args[").append(aT).append("].");
                    wrapperMethodBodies.append(Utils.getArrayArgumentMashallingLine(argumentTypes[aT], "a" + aT));
                }

                // The only argument is the array of arguments.
                argumentContent.append("args");
                wrapperMethodBodies.append('\n');
            } else {
                // Otherwise, use the vanilla calling approach.
                boolean needsNewline = false;
                for (int aT = 0; aT < argumentTypes.length; aT++) {
                    // If the argument is a string-esque type, create a jstring from it, otherwise
                    // it can be passed directly.
                    if (Utils.isCharSequence(argumentTypes[aT])) {
                        wrapperMethodBodies.append("    jstring j").append(aT).append(" = NewJavaString(env, a").append(aT).append(");\n");
                        needsNewline = true;
                        // Ensure we refer to the newly constructed Java string - not to the original
                        // parameter to the wrapper function.
                        argumentContent.append('j').append(aT);
                    } else {
                        argumentContent.append('a').append(aT);
                    }
                    if (aT != argumentTypes.length - 1) {
                        argumentContent.append(", ");
                    }
                }
                if (needsNewline) {
                    wrapperMethodBodies.append('\n');
                }
            }
        }

        wrapperMethodBodies.append("    ");
        if (!returnType.getCanonicalName().equals("void")) {
            if (isObjectReturningMethod) {
                wrapperMethodBodies.append("jobject");
            } else {
                wrapperMethodBodies.append(Utils.getCReturnType(returnType));
            }
            wrapperMethodBodies.append(" temp = ");
        }

        boolean isStaticJavaMethod = Utils.isMethodStatic(aMethod);

        // The call into Java
        wrapperMethodBodies.append("env->");
        wrapperMethodBodies.append(Utils.getCallPrefix(returnType, isStaticJavaMethod));
        if (argumentTypes.length > 2) {
            wrapperMethodBodies.append('A');
        }

        wrapperMethodBodies.append('(');
        // If the underlying Java method is nonstatic, we provide the target object to the JNI.
        if (!isStaticJavaMethod) {
            wrapperMethodBodies.append("aTarget, ");
        } else {
            // If the stub to be generated is static, we need to use the singleton to access the class
            // reference.
            if (aIsStaticBridgeMethod) {
                wrapperMethodBodies.append("sBridge->");
            }
            // If this is a static underlyin Java method, we need to use the class reference in our
            // call.
            wrapperMethodBodies.append(Utils.getClassReferenceName(aClass)).append(", ");
        }

        // Write the method id out..
        if (aIsStaticBridgeMethod) {
            wrapperMethodBodies.append("sBridge->");
        }
        wrapperMethodBodies.append('j');
        wrapperMethodBodies.append(aCMethodName);

        // Tack on the arguments, if any..
        wrapperMethodBodies.append(argumentContent);
        wrapperMethodBodies.append(");\n\n");

        // Check for exception and return the failure value..
        wrapperMethodBodies.append("    if (env->ExceptionCheck()) {\n" +
                                   "        ALOG_BRIDGE(\"Exceptional exit of: %s\", __PRETTY_FUNCTION__);\n" +
                                   "        env->ExceptionDescribe();\n" +
                                   "        env->ExceptionClear();\n" +
                                   "        env->PopLocalFrame(NULL);\n" +
                                   "        return").append(Utils.getFailureReturnForType(returnType)).append(";\n" +
                                   "    }\n");

        // If we're returning an object, pop the callee's stack frame extracting our ref as the return
        // value.
        if (isObjectReturningMethod) {
            wrapperMethodBodies.append("    ");
            wrapperMethodBodies.append(Utils.getCReturnType(returnType));
            wrapperMethodBodies.append(" ret = static_cast<").append(Utils.getCReturnType(returnType)).append(">(env->PopLocalFrame(temp));\n" +
                                       "    return ret;\n");
        } else if (!returnType.getCanonicalName().equals("void")) {
            // If we're a primitive-returning function, just return the directly-obtained primative
            // from the call to Java.
            wrapperMethodBodies.append("    env->PopLocalFrame(NULL);\n" +
                                       "    return temp;\n");
        } else {
            // If we don't return anything, just pop the stack frame and move on with life.
            wrapperMethodBodies.append("    env->PopLocalFrame(NULL);\n");
        }
        wrapperMethodBodies.append("}\n");
    }

    /**
     * Generates the code to get the method id of the given method on startup.
     *
     * @param aCMethodName    The C method name of the method being generated.
     * @param aJavaMethodName The name of the Java method being wrapped.
     * @param aMethod         The Java method being wrapped.
     */
    private void writeStartupCode(String aCMethodName, String aJavaMethodName, Method aMethod, Class<?> aClass) {
        wrapperStartupCode.append("    j");
        wrapperStartupCode.append(aCMethodName);
        wrapperStartupCode.append(" = get");
        if (Utils.isMethodStatic(aMethod)) {
            wrapperStartupCode.append("Static");
        }
        wrapperStartupCode.append("Method(\"");
        wrapperStartupCode.append(aJavaMethodName);
        wrapperStartupCode.append("\", \"");
        wrapperStartupCode.append(Utils.getTypeSignatureString(aMethod));
        wrapperStartupCode.append("\");\n");
    }

    /**
     * Create a method id field in the header file for the C method name provided.
     *
     * @param aMethodName C method name to generate a method id field for.
     */
    private void writeHeaderField(String aMethodName) {
        headerFields.append("jmethodID j");
        headerFields.append(aMethodName);
        headerFields.append(";\n");
    }

    /**
     * Get the finalised bytes to go into the generated wrappers file.
     *
     * @return The bytes to be written to the wrappers file.
     */
    public byte[] getWrapperFileContents() {
        wrapperStartupCode.append("}\n");
        wrapperStartupCode.append(wrapperMethodBodies);
        wrapperMethodBodies.setLength(0);
        return wrapperStartupCode.toString().getBytes();
    }

    /**
     * Get the finalised bytes to go into the generated header file.
     *
     * @return The bytes to be written to the header file.
     */
    public byte[] getHeaderFileContents() {
        headerFields.append('\n');
        headerFields.append(headerMethods);
        headerMethods.setLength(0);
        return headerFields.toString().getBytes();
    }
}