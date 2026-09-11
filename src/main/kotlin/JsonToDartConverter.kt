package com.example.jeels_json_to_dart

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

// =============================================================================
//  JsonToDartConverter
//  Converts a raw JSON string into Dart model class source code.
//
//  Imports needed in this file (all bundled with IntelliJ platform — no extra
//  Gradle dependencies required beyond bundledPlugin("com.intellij.modules.json")):
//    com.google.gson.JsonArray
//    com.google.gson.JsonElement
//    com.google.gson.JsonObject
//    com.google.gson.JsonParser
// =============================================================================
object JsonToDartConverter {

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    data class Options(
        val generateToJson: Boolean = true,
        val nullSafeChecks: Boolean = true,
        val snakeToCamel:   Boolean = true
    )

    /**
     * Convert [jsonString] into Dart model source code.
     *
     * @param jsonString   Raw JSON (object or array).
     * @param rootClass    Name of the root Dart class (e.g. "CreditNotesResponseModel").
     * @param options      Feature toggles.
     * @return             Complete Dart source — root class first, nested classes after.
     */
    fun convert(jsonString: String, rootClass: String, options: Options): String {
        val element = JsonParser.parseString(jsonString.trim())

        val classes = mutableListOf<DartClass>()

        when {
            element.isJsonObject -> buildClass(rootClass, element.asJsonObject, classes, options)
            element.isJsonArray  -> {
                // Merge all top-level array elements into one root class shape
                val merged = mergeObjects(element.asJsonArray)
                buildClass(rootClass, merged, classes, options)
            }
            else -> throw IllegalArgumentException("Root JSON must be an object or array of objects.")
        }

        return classes.joinToString("\n\n") { it.render(options) }
    }

    // -------------------------------------------------------------------------
    //  Class builder (recursive)
    // -------------------------------------------------------------------------

    private fun buildClass(
        className: String,
        obj: JsonObject,
        out: MutableList<DartClass>,
        options: Options
    ) {
        // Record position BEFORE recursing so this class can be inserted
        // in front of any nested classes that recursion appends.
        // Result: pre-order depth-first — root first, then each field's full subtree.
        val insertIndex = out.size

        val fields = mutableListOf<DartField>()

        for ((rawKey, value) in obj.entrySet()) {
            val dartName = if (options.snakeToCamel) snakeToCamel(rawKey) else rawKey
            val dartType = inferType(
                rawKey    = rawKey,
                dartName  = dartName,
                value     = value,
                parentCls = className,
                out       = out,
                options   = options
            )
            fields += DartField(jsonKey = rawKey, dartName = dartName, dartType = dartType)
        }

        // Insert at saved index — this class appears before its nested children
        out.add(insertIndex, DartClass(name = className, fields = fields))
    }

    // -------------------------------------------------------------------------
    //  Type inference
    // -------------------------------------------------------------------------

    private fun inferType(
        rawKey:    String,
        dartName:  String,
        value:     JsonElement,
        parentCls: String,
        out:       MutableList<DartClass>,
        options:   Options
    ): DartType = when {
        value.isJsonNull      -> DartType.Dynamic
        value.isJsonPrimitive -> primitiveDartType(value)
        value.isJsonObject    -> {
            // Full-path nested class name: e.g. parentCls=RootModel, dartName=data → RootModelData
            val nestedName = nestedClassName(parentCls, dartName, isList = false)
            buildClass(nestedName, value.asJsonObject, out, options)
            DartType.Obj(nestedName)
        }
        value.isJsonArray     -> inferArrayType(rawKey, dartName, value.asJsonArray, parentCls, out, options)
        else                  -> DartType.Dynamic
    }

    private fun inferArrayType(
        rawKey:    String,
        dartName:  String,
        arr:       JsonArray,
        parentCls: String,
        out:       MutableList<DartClass>,
        options:   Options
    ): DartType {
        // Empty array → List<dynamic>?
        if (arr.size() == 0) return DartType.ListOf(DartType.Dynamic)

        val firstNonNull = arr.firstOrNull { !it.isJsonNull }
            ?: return DartType.ListOf(DartType.Dynamic)   // all-null array

        return when {
            firstNonNull.isJsonObject -> {
                // Merge keys from ALL object elements so inconsistent shapes are handled
                val merged     = mergeObjects(arr)
                val nestedName = nestedClassName(parentCls, dartName, isList = true)
                buildClass(nestedName, merged, out, options)
                DartType.ListOf(DartType.Obj(nestedName))
            }
            firstNonNull.isJsonPrimitive -> {
                // Scan ALL non-null elements; if any element has a different primitive
                // type, fall back to List<dynamic> rather than trusting only the first.
                val inferredType = primitiveDartType(firstNonNull)
                val allSameType  = arr
                    .filter { !it.isJsonNull && it.isJsonPrimitive }
                    .all  { primitiveDartType(it) == inferredType }
                if (allSameType) DartType.ListOf(inferredType)
                else             DartType.ListOf(DartType.Dynamic)
            }
            else -> DartType.ListOf(DartType.Dynamic)
        }
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    /** Merge all JsonObject elements of an array into one superset JsonObject. */
    private fun mergeObjects(arr: JsonArray): JsonObject {
        val merged = JsonObject()
        for (element in arr) {
            if (!element.isJsonObject) continue
            for ((k, v) in element.asJsonObject.entrySet()) {
                // Only set the key if we don't have a non-null value for it yet
                if (!merged.has(k) || merged.get(k).isJsonNull) {
                    merged.add(k, v)
                }
            }
        }
        return merged
    }

    /** Map a JsonPrimitive value to the appropriate DartType. */
    private fun primitiveDartType(el: JsonElement): DartType {
        val p = el.asJsonPrimitive
        return when {
            p.isBoolean -> DartType.Bool
            p.isNumber  -> if (p.asNumber.toString().contains('.')) DartType.Double else DartType.Int
            else        -> DartType.Str
        }
    }

    /**
     * Build the nested class name using the *full* parent path as prefix.
     *
     * Rules:
     * - Convert [dartName] to PascalCase.
     * - For lists, singularize by stripping a trailing lowercase 's' (simple heuristic).
     * - Concatenate: "$parentCls${pascal}".
     *
     * Examples:
     *   parentCls=RootModel, dartName=data        → RootModelData
     *   parentCls=RootModelData, dartName=pagination → RootModelDataPagination
     *   parentCls=RootModelData, dartName=creditnotes (list) → RootModelDataCreditnote
     */
    private fun nestedClassName(parentCls: String, dartName: String, isList: Boolean): String {
        var pascal = dartName.replaceFirstChar { it.uppercaseChar() }
        if (isList && pascal.endsWith('s') && pascal.length > 1) {
            // Strip trailing lowercase 's' to singularize — e.g. Orders→Order, Items→Item
            pascal = pascal.dropLast(1)
        }
        return "$parentCls$pascal"
    }

    /** Convert snake_case (or kebab-case) to camelCase. */
    private fun snakeToCamel(s: String): String {
        if (!s.contains('_') && !s.contains('-')) return s
        return s.split('_', '-').mapIndexed { i, part ->
            if (i == 0) part else part.replaceFirstChar { it.uppercaseChar() }
        }.joinToString("")
    }
}

// =============================================================================
//  DartType — sealed hierarchy
// =============================================================================

sealed class DartType {
    object Bool    : DartType()
    object Int     : DartType()
    object Double  : DartType()
    object Str     : DartType()
    object Dynamic : DartType()
    data class Obj(val cls: String)         : DartType()
    data class ListOf(val el: DartType)     : DartType()

    fun dartName(): String = when (this) {
        is Bool    -> "bool"
        is Int     -> "int"
        is Double  -> "double"
        is Str     -> "String"
        is Dynamic -> "dynamic"
        is Obj     -> cls
        is ListOf  -> "List<${el.dartName()}>"
    }

    val isPrimitive get() = this is Bool || this is Int || this is Double || this is Str
}

// =============================================================================
//  DartField / DartClass
// =============================================================================

data class DartField(
    val jsonKey:  String,   // original JSON key (snake_case preserved)
    val dartName: String,   // Dart identifier (camelCase if option on)
    val dartType: DartType
)

data class DartClass(
    val name:   String,
    val fields: List<DartField>
) {

    // -------------------------------------------------------------------------
    //  Render to Dart source
    // -------------------------------------------------------------------------

    fun render(options: JsonToDartConverter.Options): String = buildString {

        // ── class header ──────────────────────────────────────────────────────
        appendLine("class $name {")

        if (fields.isEmpty()) {
            // ── compact empty-class layout ────────────────────────────────────
            //  class MyModelEmptyObject {
            //    MyModelEmptyObject();
            //
            //    MyModelEmptyObject.fromJson(Map<String, dynamic> json);
            //
            //    Map<String, dynamic> toJson() => {};
            //  }
            appendLine("  $name();")
            appendLine()
            appendLine("  $name.fromJson(Map<String, dynamic> json);")
            if (options.generateToJson) {
                appendLine()
                appendLine("  Map<String, dynamic> toJson() => {};")
            }
        } else {
            // ── final fields ──────────────────────────────────────────────────
            for (f in fields) {
                appendLine("  final ${f.dartType.dartName()}? ${f.dartName};")
            }
            appendLine()

            // ── named-optional constructor ─────────────────────────────────────
            appendLine("  $name({")
            for (f in fields) {
                appendLine("    this.${f.dartName},")
            }
            appendLine("  });")
            appendLine()

            // ── fromJson initializer-list ──────────────────────────────────────
            appendLine("  $name.fromJson(Map<String, dynamic> json)")
            fields.forEachIndexed { i, f ->
                val prefix = if (i == 0) "      : " else "  , "
                append(prefix)
                append(f.dartName)
                append(" = ")
                appendLine(fromJsonExpr(f, options))
            }
            appendLine("  ;")
            appendLine()

            // ── toJson ────────────────────────────────────────────────────────
            if (options.generateToJson) {
                appendLine("  Map<String, dynamic> toJson() => {")
                for (f in fields) {
                    appendLine("    '${f.jsonKey}': ${toJsonExpr(f)},")
                }
                appendLine("  };")
            }
        }

        append("}")
    }

    // ── fromJson RHS expression ───────────────────────────────────────────────

    private fun fromJsonExpr(f: DartField, options: JsonToDartConverter.Options): String {
        val k = "'${f.jsonKey}'"   // always the original JSON key

        // Without null-safety checks: plain cast / direct access
        if (!options.nullSafeChecks) {
            return when (val t = f.dartType) {
                is DartType.Obj    -> "${t.cls}.fromJson(json[$k] as Map<String, dynamic>)"
                is DartType.ListOf -> {
                    val el = t.el
                    if (el is DartType.Obj)
                        "(json[$k] as List?)?.map((e) => ${el.cls}.fromJson(e as Map<String, dynamic>)).toList()"
                    else
                        "(json[$k] as List?)?.cast<${el.dartName()}>().toList()"
                }
                else -> "json[$k] as ${f.dartType.dartName()}?"
            }
        }

        // With null-safety checks (default)
        return when (val t = f.dartType) {
            is DartType.Bool,
            is DartType.Int,
            is DartType.Str -> {
                val dn = t.dartName()
                "json[$k] is $dn ? json[$k] as $dn : null"
            }
            // double: accept both int and double JSON values (whole numbers parse as int)
            is DartType.Double -> "json[$k] is num ? (json[$k] as num).toDouble() : null"
            is DartType.Dynamic -> "json[$k]"
            is DartType.Obj     ->
                "json[$k] is Map<String, dynamic> ? ${t.cls}.fromJson(json[$k]) : null"
            is DartType.ListOf  -> {
                val el = t.el
                when {
                    el is DartType.Obj ->
                        // Multi-line for readability
                        "json[$k] is List\n          ? (json[$k] as List)\n              .map((e) => e is Map<String, dynamic> ? ${el.cls}.fromJson(e) : null)\n              .whereType<${el.cls}>()\n              .toList()\n          : null"
                    el.isPrimitive ->
                        "json[$k] is List ? List<${el.dartName()}>.from(json[$k]) : null"
                    else ->
                        "json[$k] is List ? List<dynamic>.from(json[$k]) : null"
                }
            }
        }
    }

    // ── toJson RHS expression ─────────────────────────────────────────────────

    private fun toJsonExpr(f: DartField): String = when (val t = f.dartType) {
        is DartType.Obj    -> "${f.dartName}?.toJson()"
        is DartType.ListOf -> {
            if (t.el is DartType.Obj)
                "${f.dartName}?.map((e) => e.toJson()).toList()"
            else
                f.dartName
        }
        else -> f.dartName
    }
}
