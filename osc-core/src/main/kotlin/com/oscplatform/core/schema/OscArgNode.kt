package com.oscplatform.core.schema

sealed interface OscArgNode {
  val name: String
}

data class ScalarArgNode(
    override val name: String,
    val type: OscType,
    val role: ScalarRole = ScalarRole.VALUE,
) : OscArgNode

enum class ScalarRole {
  VALUE,
  LENGTH,
}

data class ArrayArgNode(
    override val name: String,
    val length: LengthSpec,
    val item: ArrayItemSpec,
) : OscArgNode

sealed interface LengthSpec {
  data class Fixed(
      val size: Int,
  ) : LengthSpec

  data class FromField(
      val fieldName: String,
  ) : LengthSpec
}

sealed interface ArrayItemSpec {
  data class ScalarItem(
      val type: OscType,
  ) : ArrayItemSpec

  data class TupleItem(
      val fields: List<TupleFieldSpec>,
  ) : ArrayItemSpec
}

data class TupleFieldSpec(
    val name: String,
    val type: OscType,
)

internal object OscArgNodeValidator {
  fun validate(path: String, args: List<OscArgNode>) {
    val duplicateNames = args.groupBy { it.name }.filterValues { it.size > 1 }.keys
    require(duplicateNames.isEmpty()) {
      "Duplicate args for '$path': ${duplicateNames.joinToString()}"
    }

    args.forEachIndexed { index, node ->
      require(node.name.isNotBlank()) { "Arg name cannot be blank in '$path'" }

      when (node) {
        is ScalarArgNode -> {
          // No extra constraints for scalar value fields.
        }

        is ArrayArgNode -> {
          when (val length = node.length) {
            is LengthSpec.Fixed -> {
              require(length.size >= 0) {
                "Array length must be >= 0 for '${node.name}' in '$path'"
              }
            }

            is LengthSpec.FromField -> {
              require(length.fieldName.isNotBlank()) {
                "lengthFrom cannot be blank for '${node.name}' in '$path'"
              }

              val referenced =
                  args.take(index).firstOrNull { it.name == length.fieldName }
                      ?: throw IllegalArgumentException(
                          "lengthFrom '${length.fieldName}' for '${node.name}' must reference a previous scalar LENGTH field in '$path'",
                      )

              require(
                  referenced is ScalarArgNode &&
                      referenced.role == ScalarRole.LENGTH &&
                      referenced.type == OscType.INT) {
                    "lengthFrom '${length.fieldName}' for '${node.name}' must reference INT scalar with role=LENGTH in '$path'"
                  }
            }
          }

          when (val item = node.item) {
            is ArrayItemSpec.ScalarItem -> {
              // Scalar array items are fully specified by type.
            }

            is ArrayItemSpec.TupleItem -> {
              require(item.fields.isNotEmpty()) {
                "Tuple fields cannot be empty for '${node.name}' in '$path'"
              }
              val duplicateTupleNames =
                  item.fields.groupBy { it.name }.filterValues { it.size > 1 }.keys
              require(duplicateTupleNames.isEmpty()) {
                "Duplicate tuple field names for '${node.name}' in '$path': ${duplicateTupleNames.joinToString()}"
              }
              item.fields.forEach { field ->
                require(field.name.isNotBlank()) {
                  "Tuple field name cannot be blank for '${node.name}' in '$path'"
                }
              }
            }
          }
        }
      }
    }
  }
}
