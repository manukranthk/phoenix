/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *     Neither the name of Salesforce.com nor the names of its contributors may 
 *     be used to endorse or promote products derived from this software without 
 *     specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE 
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, 
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.phoenix.parse;

import java.lang.annotation.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.*;

import org.apache.http.annotation.Immutable;


import com.google.common.collect.ImmutableSet;
import com.salesforce.phoenix.compile.StatementContext;
import com.salesforce.phoenix.expression.Expression;
import com.salesforce.phoenix.expression.LiteralExpression;
import com.salesforce.phoenix.expression.function.AggregateFunction;
import com.salesforce.phoenix.expression.function.FunctionExpression;
import com.salesforce.phoenix.schema.*;
import com.salesforce.phoenix.util.SchemaUtil;



/**
 * 
 * Node representing a function expression in SQL
 *
 * @author jtaylor
 * @since 0.1
 */
public class FunctionParseNode extends CompoundParseNode {
    private final String name;
    private final BuiltInFunctionInfo info;
    private final boolean isConstant;
    
    FunctionParseNode(String name, List<ParseNode> children, BuiltInFunctionInfo info) {
        super(children);
        this.name = SchemaUtil.normalizeIdentifier(name);
        this.info = info;
        boolean isConstant = true;
        for (ParseNode child : children) {
            if (!child.isConstant()) {
                isConstant = false;
                break;
            }
        }
        this.isConstant = isConstant;
    }

    public BuiltInFunctionInfo getInfo() {
        return info;
    }
    
    public String getName() {
        return name;
    }

    @Override
    public boolean isConstant() {
        return isConstant;
    }
    
    @Override
    public <T> T accept(ParseNodeVisitor<T> visitor) throws SQLException {
        List<T> l = Collections.emptyList();
        if (visitor.visitEnter(this)) {
            l = acceptChildren(visitor);
        }
        return visitor.visitLeave(this, l);
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(name + "(");
        for (ParseNode child : getChildren()) {
            buf.append(child.toString());
            buf.append(',');
        }
        buf.setLength(buf.length()-1);
        buf.append(')');
        return buf.toString();
    }

    public boolean isAggregate() {
        return false;
    }
    
    /**
     * Determines whether or not we can collapse a function expression to null if a required
     * parameter is null.
     * @param context
     * @param index index of parameter
     * @return true if when the parameter at index is null, the function always evaluates to null
     *  and false otherwise.
     * @throws SQLException
     */
    public boolean evalToNullIfParamIsNull(StatementContext context, int index) throws SQLException {
        return true;
    }
    

    private static Constructor<? extends FunctionParseNode> getParseNodeCtor(Class<? extends FunctionParseNode> clazz) throws Exception {
        Constructor<? extends FunctionParseNode> ctor = clazz.getDeclaredConstructor(String.class, List.class, BuiltInFunctionInfo.class);
        ctor.setAccessible(true);
        return ctor;
    }
    
    private static Constructor<? extends FunctionExpression> getExpressionCtor(Class<? extends FunctionExpression> clazz) throws Exception {
        Constructor<? extends FunctionExpression> ctor = clazz.getDeclaredConstructor(List.class);
        ctor.setAccessible(true);
        return ctor;
    }
    
    public List<Expression> validate(List<Expression> children, StatementContext context) throws SQLException {
        BuiltInFunctionInfo info = this.getInfo();
        BuiltInFunctionArgInfo[] args = info.getArgs();
        if (args.length > children.size()) {
            List<Expression> moreChildren = new ArrayList<Expression>(children);
            for (int i = children.size(); i < info.getArgs().length; i++) {
                moreChildren.add(LiteralExpression.newConstant(null, args[i].allowedTypes.length == 0 ? null :  args[i].allowedTypes[0]));
            }
            children = moreChildren;
        }
        List<ParseNode> nodeChildren = this.getChildren();
        for (int i = 0; i < children.size(); i++) {
            BindParseNode bindNode = null;
            PDataType[] allowedTypes = args[i].getAllowedTypes();
            // Check if the node is a bind parameter, and set the parameter
            // metadata based on the function argument annotation. Check to
            // make sure we're not looking past the end of the list of
            // child expression nodes, which can happen if the function
            // invocation hasn't specified all arguments and is using default
            // values.
            if (i < nodeChildren.size() && nodeChildren.get(i) instanceof BindParseNode) {
                bindNode = (BindParseNode)nodeChildren.get(i);
            }
            // If the child type is null, then the expression is unbound.
            // Skip any validation, since we either 1) have a default value
            // which has already been validated, 2) attempting to get the
            // parameter metadata, or 3) have an unbound parameter which
            // will be detected futher downstream.
            if (children.get(i).getDataType() == null /* null used explicitly in query */ || i >= nodeChildren.size() /* argument left off */) {
                // Replace the unbound expression with the default value expression if specified
                if (args[i].getDefaultValue() != null) {
                    Expression defaultValue = args[i].getDefaultValue();
                    children.set(i, defaultValue);
                    // Set the parameter metadata if this is a bind parameter
                    if (bindNode != null) {
                        context.getBindManager().addParamMetaData(bindNode, defaultValue);
                    }
                } else if (bindNode != null) {
                    // Otherwise if the node is a bind parameter and we have type information
                    // based on the function argument annonation set the parameter meta data.
                    if (children.get(i).getDataType() == null) {
                        if (allowedTypes.length > 0) {
                            context.getBindManager().addParamMetaData(bindNode, LiteralExpression.newConstant(null, allowedTypes[0]));
                        }
                    } else { // Use expression as is, since we already have the data type set
                        context.getBindManager().addParamMetaData(bindNode, children.get(i));
                    }
                }
            } else {
                if (allowedTypes.length > 0) {
                    boolean isCoercible = false;
                    for (PDataType type : allowedTypes) {
                        if (children.get(i).getDataType().isCoercibleTo(type)) {
                            isCoercible = true;
                            break;
                        }
                    }
                    if (!isCoercible) {
                        throw new ArgumentTypeMismatchException(Arrays.toString(args[i].getAllowedTypes()),
                                children.get(i).getDataType().toString(), info.getName() + " argument " + (i + 1));
                    }
                }
                if (args[i].isConstant() && ! (children.get(i) instanceof LiteralExpression) ) {
                    throw new ArgumentTypeMismatchException("constant", children.get(i).toString(), info.getName() + " argument " + (i + 1));
                }
                if (!args[i].getAllowedValues().isEmpty()) {
                    Object value = ((LiteralExpression)children.get(i)).getValue();
                    if (!args[i].getAllowedValues().contains(value.toString().toUpperCase())) {
                        throw new ArgumentTypeMismatchException(Arrays.toString(args[i].getAllowedValues().toArray(new String[0])),
                                value.toString(), info.getName() + " argument " + (i + 1));
                    }
                }
            }
        }
        return children;
    }
    
    /**
     * Entry point for parser to instantiate compiled representation of built-in function
     * @param children Compiled expressions for child nodes
     * @param context Query context for accessing state shared across the processing of multiple clauses
     * @return compiled representation of built-in function
     * @throws SQLException
     */
    public FunctionExpression create(List<Expression> children, StatementContext context) throws SQLException {
        try {
            return info.getFuncCtor().newInstance(children);
        } catch (InstantiationException e) {
            throw new SQLException(e);
        } catch (IllegalAccessException e) {
            throw new SQLException(e);
        } catch (IllegalArgumentException e) {
            throw new SQLException(e);
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof SQLException) {
                throw (SQLException) e.getTargetException();
            }
            throw new SQLException(e);
        }
    }
    
    /**
     * Marker used to indicate that a class should be used by DirectFunctionExpressionExec below
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public
    @interface BuiltInFunction {
        String name();
        Argument[] args() default {};
        Class<? extends FunctionParseNode> nodeClass() default FunctionParseNode.class;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public
    @interface Argument {
        PDataType[] allowedTypes() default {};
        boolean isConstant() default false;
        String defaultValue() default "";
        String enumeration() default "";
    }
    
    /**
     * Structure used to hold parse-time information about Function implementation classes
     */
    @Immutable
    public static final class BuiltInFunctionInfo {
        private final String name;
        private final Constructor<? extends FunctionExpression> funcCtor;
        private final Constructor<? extends FunctionParseNode> nodeCtor;
        private final BuiltInFunctionArgInfo[] args;
        private final boolean isAggregate;
        private final int requiredArgCount;

        BuiltInFunctionInfo(Class<? extends FunctionExpression> f, BuiltInFunction d) throws Exception {
            this.name = SchemaUtil.normalizeIdentifier(d.name());
            this.funcCtor = d.nodeClass() == FunctionParseNode.class ? getExpressionCtor(f) : null;
            this.nodeCtor = d.nodeClass() == FunctionParseNode.class ? null : getParseNodeCtor(d.nodeClass());
            this.args = new BuiltInFunctionArgInfo[d.args().length];
            int requiredArgCount = 0;
            for (int i = 0; i < args.length; i++) {
                this.args[i] = new BuiltInFunctionArgInfo(d.args()[i]);
                if (requiredArgCount < i && this.args[i].getDefaultValue() != null) {
                    requiredArgCount = i;
                }
            }
            this.requiredArgCount = requiredArgCount;
            this.isAggregate = AggregateFunction.class.isAssignableFrom(f);
        }

        public int getRequiredArgCount() {
            return requiredArgCount;
        }
        
        public String getName() {
            return name;
        }

        public Constructor<? extends FunctionExpression> getFuncCtor() {
            return funcCtor;
        }

        public Constructor<? extends FunctionParseNode> getNodeCtor() {
            return nodeCtor;
        }

        public boolean isAggregate() {
            return isAggregate;
        }
        
        public BuiltInFunctionArgInfo[] getArgs() {
            return args;
        }
    }
    
    @Immutable
    public static class BuiltInFunctionArgInfo {
        private static final PDataType[] ENUMERATION_TYPES = new PDataType[] {PDataType.VARCHAR};
        private final PDataType[] allowedTypes;
        private final boolean isConstant;
        private final Set<String> allowedValues; // Enumeration of possible values
        private final LiteralExpression defaultValue;
        
        @SuppressWarnings({ "unchecked", "rawtypes" })
        BuiltInFunctionArgInfo(Argument argument) {
            
            if (argument.enumeration().length() > 0) {
                this.isConstant = true;
                this.defaultValue = null;
                this.allowedTypes = ENUMERATION_TYPES;
                Class<?> clazz = null;
                String packageName = FunctionExpression.class.getPackage().getName();
                try {
                    clazz = Class.forName(packageName + "." + argument.enumeration());
                } catch (ClassNotFoundException e) {
                    try {
                        clazz = Class.forName(argument.enumeration());
                    } catch (ClassNotFoundException e1) {
                    }
                }
                if (clazz == null || !clazz.isEnum()) {
                    throw new IllegalStateException("The enumeration annotation '" + argument.enumeration() + "' does not resolve to a enumeration class");
                }
                Class<? extends Enum> enumClass = (Class<? extends Enum>)clazz;
				Enum[] enums = enumClass.getEnumConstants();
                ImmutableSet.Builder<String> builder = ImmutableSet.builder();
                for (Enum en : enums) {
                    builder.add(en.name());
                }
                allowedValues = builder.build();
            } else {
                this.allowedValues = Collections.emptySet();
                this.isConstant = argument.isConstant();
                this.allowedTypes = argument.allowedTypes();
                String defaultStringValue = argument.defaultValue();
                if (defaultStringValue.length() > 0) {
                    SQLParser parser = new SQLParser(defaultStringValue);
                    try {
                        LiteralParseNode node = parser.parseLiteral();
                        LiteralExpression defaultValue = LiteralExpression.newConstant(node.getValue(), this.allowedTypes[0]);
                        if (this.getAllowedTypes().length > 0) {
                            for (PDataType type : this.getAllowedTypes()) {
                                if (defaultValue.getDataType() == null || defaultValue.getDataType().isCoercibleTo(type, node.getValue())) {
                                    this.defaultValue = LiteralExpression.newConstant(node.getValue(), type);
                                    return;
                                }
                            }
                            throw new IllegalStateException("Unable to coerce default value " + argument.defaultValue() + " to any of the allowed types of " + Arrays.toString(this.getAllowedTypes()));
                        }
                        this.defaultValue = defaultValue;
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    this.defaultValue = null;
                }
            }
        }

        public boolean isConstant() {
            return isConstant;
        }

        public LiteralExpression getDefaultValue() {
            return defaultValue;
        }

        public PDataType[] getAllowedTypes() {
            return allowedTypes;
        }
        
        public Set<String> getAllowedValues() {
            return allowedValues;
        }
    }    
}