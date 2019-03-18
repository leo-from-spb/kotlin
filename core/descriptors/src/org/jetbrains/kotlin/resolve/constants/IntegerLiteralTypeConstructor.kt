/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.constants

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.types.*

class IntegerLiteralTypeConstructor : TypeConstructor {
    companion object {
        fun findCommonSuperType(types: Collection<SimpleType>): SimpleType? =
            findCommonSuperTypeOrIntersectionType(types, Companion.Mode.COMMON_SUPER_TYPE)

        fun findIntersectionType(types: Collection<SimpleType>): SimpleType? =
            findCommonSuperTypeOrIntersectionType(types, Companion.Mode.INTERSECTION_TYPE)

        private enum class Mode {
            COMMON_SUPER_TYPE, INTERSECTION_TYPE
        }

        /**
         * intersection(ILT(types), PrimitiveType) = commonSuperType(ILT(types), PrimitiveType) =
         *      PrimitiveType  in types  -> PrimitiveType
         *      PrimitiveType !in types -> null
         *
         * intersection(ILT(types_1), ILT(types_2)) = ILT(types_1 union types_2)
         *
         * commonSuperType(ILT(types_1), ILT(types_2)) = ILT(types_1 intersect types_2)
         */
        private fun findCommonSuperTypeOrIntersectionType(types: Collection<SimpleType>, mode: Mode): SimpleType? {
            if (types.isEmpty()) return null
            return types.reduce { left: SimpleType?, right -> fold(left, right, mode) }
        }

        private fun fold(left: SimpleType?, right: SimpleType?, mode: Mode): SimpleType? {
            if (left == null || right == null) return null
            val leftConstructor = left.constructor
            val rightConstructor = right.constructor
            return when {
                leftConstructor is IntegerLiteralTypeConstructor && rightConstructor is IntegerLiteralTypeConstructor ->
                    fold(leftConstructor, rightConstructor, mode)

                leftConstructor is IntegerLiteralTypeConstructor -> fold(leftConstructor, right)
                rightConstructor is IntegerLiteralTypeConstructor -> fold(rightConstructor, left)
                else -> null
            }
        }

        private fun fold(left: IntegerLiteralTypeConstructor, right: IntegerLiteralTypeConstructor, mode: Mode): SimpleType? {
            val possibleTypes = when (mode) {
                Mode.COMMON_SUPER_TYPE -> left.possibleTypes intersect right.possibleTypes
                Mode.INTERSECTION_TYPE -> left.possibleTypes union right.possibleTypes
            }
            val constructor = IntegerLiteralTypeConstructor(left.value, left.module, possibleTypes)
            return KotlinTypeFactory.integerLiteralType(Annotations.EMPTY, constructor, false)
        }

        private fun fold(left: IntegerLiteralTypeConstructor, right: SimpleType): SimpleType? =
            if (right in left.possibleTypes) right else null

        private val ModuleDescriptor.allIntegerLiteralTypes: List<KotlinType>
            get() = allSignedLiteralTypes + allUnsignedLiteralTypes

        private val ModuleDescriptor.allSignedLiteralTypes: List<KotlinType>
            get() = listOf(builtIns.intType, builtIns.longType, builtIns.byteType, builtIns.shortType)

        private val ModuleDescriptor.allUnsignedLiteralTypes: List<KotlinType>
            get() = if (hasUnsignedTypesInModuleDependencies(this)) {
                listOf(
                    unsignedType(KotlinBuiltIns.FQ_NAMES.uInt), unsignedType(KotlinBuiltIns.FQ_NAMES.uLong),
                    unsignedType(KotlinBuiltIns.FQ_NAMES.uByte), unsignedType(KotlinBuiltIns.FQ_NAMES.uShort)
                )
            } else {
                emptyList()
            }
    }

    private val value: Long
    private val module: ModuleDescriptor
    val possibleTypes: Set<KotlinType>

    constructor(value: Long, module: ModuleDescriptor, parameters: CompileTimeConstant.Parameters) {
        this.value = value
        this.module = module

        val allPossibleTypes = module.allIntegerLiteralTypes
        val possibleTypes = mutableSetOf<KotlinType>()

        fun checkBoundsAndAddPossibleType(value: Long, kotlinType: KotlinType) {
            if (value in kotlinType.minValue()..kotlinType.maxValue()) {
                possibleTypes.add(kotlinType)
            }
        }

        fun addSignedPossibleTypes() {
            checkBoundsAndAddPossibleType(value, allPossibleTypes[0]) // Int
            possibleTypes.add(allPossibleTypes[1])                    // Long
            checkBoundsAndAddPossibleType(value, allPossibleTypes[2]) // Byte
            checkBoundsAndAddPossibleType(value, allPossibleTypes[3]) // Short
        }

        fun addUnsignedPossibleTypes() {
            checkBoundsAndAddPossibleType(value, allPossibleTypes[4]) // uInt
            possibleTypes.add(allPossibleTypes[5])                    // uLong
            checkBoundsAndAddPossibleType(value, allPossibleTypes[6]) // uByte
            checkBoundsAndAddPossibleType(value, allPossibleTypes[7]) // uShort
        }

        val isUnsigned = parameters.isUnsignedNumberLiteral
        val isConvertable = parameters.isConvertableConstVal

        if (isUnsigned || isConvertable) {
            assert(hasUnsignedTypesInModuleDependencies(module)) {
                "Unsigned types should be on classpath to create an unsigned type constructor"
            }
        }

        when {
            isConvertable -> {
                addSignedPossibleTypes()
                addUnsignedPossibleTypes()
            }

            isUnsigned -> addUnsignedPossibleTypes()

            else -> addSignedPossibleTypes()
        }

        this.possibleTypes = possibleTypes
    }

    private constructor(value: Long, module: ModuleDescriptor, possibleTypes: Set<KotlinType>) {
        this.value = value
        this.module = module
        this.possibleTypes = possibleTypes
    }

    private val type = KotlinTypeFactory.integerLiteralType(Annotations.EMPTY, this, false)

    private fun isContainsOnlyUnsignedTypes(): Boolean = module.allSignedLiteralTypes.all { it !in possibleTypes }

    private val supertypes: List<KotlinType> by lazy {
        val result = mutableListOf(TypeUtils.substituteParameters(builtIns.comparable, listOf(type)))
        if (!isContainsOnlyUnsignedTypes()) {
            result += builtIns.numberType
        }
        result
    }

    fun getApproximatedType(): KotlinType? = module.allIntegerLiteralTypes.firstOrNull { it in possibleTypes }

    override fun getParameters(): List<TypeParameterDescriptor> = emptyList()

    override fun getSupertypes(): Collection<KotlinType> = supertypes

    override fun isFinal(): Boolean = true

    override fun isDenotable(): Boolean = false

    override fun getDeclarationDescriptor(): ClassifierDescriptor? = null

    override fun getBuiltIns(): KotlinBuiltIns = module.builtIns

    override fun toString(): String {
        return "IntegerLiteralType${valueToString()}"
    }

    fun checkConstructor(constructor: TypeConstructor): Boolean = possibleTypes.any { it.constructor == constructor }

    private fun valueToString(): String = "[${possibleTypes.joinToString(",") { it.toString() }}]"
}