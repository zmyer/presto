/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.presto.type;

import com.facebook.presto.annotation.UsedByGeneratedCode;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.metadata.SqlScalarFunction;
import com.facebook.presto.metadata.SqlScalarFunctionBuilder;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.function.OperatorType;
import com.facebook.presto.spi.type.Decimals;
import com.facebook.presto.spi.type.TypeSignature;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;

import java.lang.invoke.MethodHandle;
import java.math.BigInteger;

import static com.facebook.presto.metadata.FunctionKind.SCALAR;
import static com.facebook.presto.metadata.SqlScalarFunctionBuilder.constant;
import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.facebook.presto.spi.function.OperatorType.BETWEEN;
import static com.facebook.presto.spi.function.OperatorType.EQUAL;
import static com.facebook.presto.spi.function.OperatorType.GREATER_THAN;
import static com.facebook.presto.spi.function.OperatorType.GREATER_THAN_OR_EQUAL;
import static com.facebook.presto.spi.function.OperatorType.IS_DISTINCT_FROM;
import static com.facebook.presto.spi.function.OperatorType.LESS_THAN;
import static com.facebook.presto.spi.function.OperatorType.LESS_THAN_OR_EQUAL;
import static com.facebook.presto.spi.function.OperatorType.NOT_EQUAL;
import static com.facebook.presto.spi.type.StandardTypes.BOOLEAN;
import static com.facebook.presto.spi.type.TypeSignature.parseTypeSignature;
import static com.facebook.presto.util.Reflection.methodHandle;

public class DecimalInequalityOperators
{
    private static final TypeSignature DECIMAL_SIGNATURE = parseTypeSignature("decimal(a_precision, a_scale)", ImmutableSet.of("a_precision", "a_scale"));

    private static final MethodHandle IS_RESULT_EQUAL = methodHandle(DecimalInequalityOperators.class, "getResultEqual", int.class);
    private static final MethodHandle IS_RESULT_NOT_EQUAL = methodHandle(DecimalInequalityOperators.class, "isResultNotEqual", int.class);
    private static final MethodHandle IS_RESULT_LESS_THAN = methodHandle(DecimalInequalityOperators.class, "isResultLessThan", int.class);
    private static final MethodHandle IS_RESULT_LESS_THAN_OR_EQUAL = methodHandle(DecimalInequalityOperators.class, "isResultLessThanOrEqual", int.class);
    private static final MethodHandle IS_RESULT_GREATER_THAN = methodHandle(DecimalInequalityOperators.class, "isResultGreaterThan", int.class);
    private static final MethodHandle IS_RESULT_GREATER_THAN_OR_EQUAL = methodHandle(DecimalInequalityOperators.class, "isResultGreaterThanOrEqual", int.class);

    public static final SqlScalarFunction DECIMAL_EQUAL_OPERATOR = binaryOperator(EQUAL, IS_RESULT_EQUAL);
    public static final SqlScalarFunction DECIMAL_NOT_EQUAL_OPERATOR = binaryOperator(NOT_EQUAL, IS_RESULT_NOT_EQUAL);
    public static final SqlScalarFunction DECIMAL_LESS_THAN_OPERATOR = binaryOperator(LESS_THAN, IS_RESULT_LESS_THAN);
    public static final SqlScalarFunction DECIMAL_LESS_THAN_OR_EQUAL_OPERATOR = binaryOperator(LESS_THAN_OR_EQUAL, IS_RESULT_LESS_THAN_OR_EQUAL);
    public static final SqlScalarFunction DECIMAL_GREATER_THAN_OPERATOR = binaryOperator(GREATER_THAN, IS_RESULT_GREATER_THAN);
    public static final SqlScalarFunction DECIMAL_GREATER_THAN_OR_EQUAL_OPERATOR = binaryOperator(GREATER_THAN_OR_EQUAL, IS_RESULT_GREATER_THAN_OR_EQUAL);
    public static final SqlScalarFunction DECIMAL_BETWEEN_OPERATOR = betweenOperator();
    public static final SqlScalarFunction DECIMAL_DISTINCT_FROM_OPERATOR = binaryOperatorNullable(IS_DISTINCT_FROM, IS_RESULT_NOT_EQUAL);

    private DecimalInequalityOperators() {}

    @UsedByGeneratedCode
    public static boolean getResultEqual(int comparisonResult)
    {
        return comparisonResult == 0;
    }

    @UsedByGeneratedCode
    public static boolean isResultNotEqual(int comparisonResult)
    {
        return comparisonResult != 0;
    }

    @UsedByGeneratedCode
    public static boolean isResultLessThan(int comparisonResult)
    {
        return comparisonResult < 0;
    }

    @UsedByGeneratedCode
    public static boolean isResultLessThanOrEqual(int comparisonResult)
    {
        return comparisonResult <= 0;
    }

    @UsedByGeneratedCode
    public static boolean isResultGreaterThan(int comparisonResult)
    {
        return comparisonResult > 0;
    }

    @UsedByGeneratedCode
    public static boolean isResultGreaterThanOrEqual(int comparisonResult)
    {
        return comparisonResult >= 0;
    }

    private static SqlScalarFunctionBuilder makeBinaryOperatorFunctionBuilder(OperatorType operatorType)
    {
        Signature signature = Signature.builder()
                .kind(SCALAR)
                .operatorType(operatorType)
                .argumentTypes(DECIMAL_SIGNATURE, DECIMAL_SIGNATURE)
                .returnType(parseTypeSignature(BOOLEAN))
                .build();
        return SqlScalarFunction.builder(DecimalInequalityOperators.class)
                .signature(signature);
    }

    private static SqlScalarFunction binaryOperator(OperatorType operatorType, MethodHandle getResultMethodHandle)
    {
        return makeBinaryOperatorFunctionBuilder(operatorType)
                .implementation(b -> b
                        .methods("opShortShort", "opLongLong")
                        .withExtraParameters(constant(getResultMethodHandle))
                )
                .build();
    }

    private static SqlScalarFunction binaryOperatorNullable(OperatorType operatorType, MethodHandle getResultMethodHandle)
    {
        return makeBinaryOperatorFunctionBuilder(operatorType)
                .nullableArguments(true, true)
                .nullFlags(true, true)
                .implementation(b -> b
                        .methods("opShortShortShortNullable", "opLongLongNullable")
                        .withExtraParameters(constant(getResultMethodHandle))
                )
                .build();
    }

    @UsedByGeneratedCode
    public static boolean opShortShort(long a, long b, MethodHandle getResultMethodHandle)
    {
        return invokeGetResult(getResultMethodHandle, Long.compare(a, b));
    }

    @UsedByGeneratedCode
    public static boolean opShortShortShortNullable(
            long a,
            boolean aNull,
            long b,
            boolean bNull,
            MethodHandle getResultMethodHandle)
    {
        if (aNull != bNull) {
            return true;
        }
        if (aNull) {
            return false;
        }
        return opShortShort(a, b, getResultMethodHandle);
    }

    @UsedByGeneratedCode
    public static boolean opLongLong(Slice a, Slice b, MethodHandle getResultMethodHandle)
    {
        BigInteger left = Decimals.decodeUnscaledValue(a);
        BigInteger right = Decimals.decodeUnscaledValue(b);
        return invokeGetResult(getResultMethodHandle, left.compareTo(right));
    }

    @UsedByGeneratedCode
    public static boolean opLongLongNullable(
            Slice a,
            boolean aNull,
            Slice b,
            boolean bNull,
            MethodHandle getResultMethodHandle)
    {
        if (aNull != bNull) {
            return true;
        }
        if (aNull && bNull) {
            return false;
        }
        return opLongLong(a, b, getResultMethodHandle);
    }

    private static boolean invokeGetResult(MethodHandle getResultMethodHandle, int comparisonResult)
    {
        try {
            return (boolean) getResultMethodHandle.invokeExact(comparisonResult);
        }
        catch (Throwable t) {
            Throwables.propagateIfInstanceOf(t, Error.class);
            Throwables.propagateIfInstanceOf(t, PrestoException.class);
            throw new PrestoException(GENERIC_INTERNAL_ERROR, t);
        }
    }

    private static SqlScalarFunction betweenOperator()
    {
        Signature signature = Signature.builder()
                .kind(SCALAR)
                .operatorType(BETWEEN)
                .argumentTypes(DECIMAL_SIGNATURE, DECIMAL_SIGNATURE, DECIMAL_SIGNATURE)
                .returnType(parseTypeSignature(BOOLEAN))
                .build();
        return SqlScalarFunction.builder(DecimalInequalityOperators.class)
                .signature(signature)
                .implementation(b -> b
                        .methods("betweenShortShortShort", "betweenLongLongLong")
                )
                .build();
    }

    @UsedByGeneratedCode
    public static boolean betweenShortShortShort(long value, long low, long high)
    {
        return low <= value && value <= high;
    }

    @UsedByGeneratedCode
    public static boolean betweenLongLongLong(Slice valueSlice, Slice lowSlice, Slice highSlice)
    {
        BigInteger value = Decimals.decodeUnscaledValue(valueSlice);
        BigInteger low = Decimals.decodeUnscaledValue(lowSlice);
        BigInteger high = Decimals.decodeUnscaledValue(highSlice);
        return low.compareTo(value) <= 0 && value.compareTo(high) <= 0;
    }
}
