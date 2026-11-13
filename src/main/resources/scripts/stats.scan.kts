package scripts

import de.skyrising.mc.scanner.ItemStackEntry
import de.skyrising.mc.scanner.Needle
import de.skyrising.mc.scanner.dumpItemStacks
import it.unimi.dsi.fastutil.objects.*
import java.io.PrintStream
import java.nio.file.Files
import java.util.*
import java.util.function.ToIntFunction

fun wants(name: String): Boolean = outputs.isEmpty() || name in outputs

fun csv(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        if (c == '"') sb.append('"')
        sb.append(c)
    }
    sb.append('"')
    return sb.toString()
}

val total = Object2LongOpenHashMap<Needle>()
val inventoryCounts = Object2IntOpenHashMap<ItemType>()
val stats = Object2ObjectOpenHashMap<ItemType, Object2DoubleMap<ItemType>>()
val locationIds = Object2IntLinkedOpenHashMap<Location>()
val inventoriesFile = if (wants("inventories")) PrintStream(Files.newOutputStream(outPath.resolve("inventories.csv")), false, "UTF-8") else null
val nbtEntries = Collections.synchronizedList(mutableListOf<ItemStackEntry>())

scan {
    val results = scan(needles, true)
    if (wants("nbt")) {
        for (source in itemSources()) {
            nbtEntries.addAll(dumpItemStacks(source.items, source.version))
        }
    }
    results
}

onResults {
    for (result in this) {
        val location = result.location
        val needle = result.needle
        when (needle) {
            is StatsResults -> {
                val types = needle.types
                val stride = types.size
                val matrix = needle.matrix
                for (i in 0 until stride) {
                    inventoryCounts.addTo(types[i], 1)
                    if (wants("stats")) {
                        val map = stats.computeIfAbsent(types[i], Object2ObjectFunction { Object2DoubleOpenHashMap() })
                        for (j in 0 until stride) {
                            val type = types[j]
                            val value = matrix[i * stride + j]
                            map[type] = map.getDouble(type) + value
                        }
                    }
                }
                continue
            }
            is ItemType -> {
                if (wants("inventories")) {
                    val loc = if (location is SubLocation) location else SubLocation(location, 0)
                    val locId = locationIds.computeIfAbsent(loc.parent, ToIntFunction { locationIds.size })
                    inventoriesFile!!.print(locId)
                    inventoriesFile.print(',')
                    inventoriesFile.print(loc.index)
                    inventoriesFile.print(',')
                    inventoriesFile.print(needle.id)
                    inventoriesFile.print(',')
                    inventoriesFile.print(result.count)
                    inventoriesFile.println()
                }
            }
        }
        if (location is SubLocation) continue
        total.addTo(needle, result.count)
    }
    inventoriesFile?.flush()
}

after {
    inventoriesFile?.close()
    if (wants("locations")) {
        PrintStream(Files.newOutputStream(outPath.resolve("locations.csv")), false, "UTF-8").use { locationsFile ->
            locationsFile.println("Id,Location")
            for ((location, id) in locationIds) {
                locationsFile.print(id)
                locationsFile.print(',')
                locationsFile.println(csv(location.toString()))
            }
        }
    }
    val types = total.keys.filterIsInstance<ItemType>().sorted()
    if (wants("counts")) {
        PrintStream(Files.newOutputStream(outPath.resolve("counts.csv")), false, "UTF-8").use { countsFile ->
            countsFile.println("Type,Total,Number of Inventories")
            for (type in types) {
                countsFile.print(type.format())
                countsFile.print(',')
                countsFile.print(total.getLong(type))
                countsFile.print(',')
                countsFile.println(inventoryCounts.getInt(type))
            }
        }
    }
    if (wants("stats")) {
        PrintStream(Files.newOutputStream(outPath.resolve("stats.csv")), false, "UTF-8").use { statsFile ->
            for (type in types) {
                statsFile.print(',')
                statsFile.print(type.format())
            }
            statsFile.println()
            for (a in types) {
                statsFile.print(a.id)
                val map = stats[a]!!
                val sum = map.values.sum()
                for (b in types) {
                    statsFile.print(',')
                    val value = map.getDouble(b)
                    statsFile.printf(Locale.ROOT, "%1.5f", value / sum)
                }
                statsFile.println()
            }
        }
    }
    if (wants("nbt")) {
        val aggregated = LinkedHashMap<Pair<String, String>, Long>()
        for (e in nbtEntries) {
            val key = e.id to e.nbt
            aggregated[key] = (aggregated[key] ?: 0L) + e.count
        }
        PrintStream(Files.newOutputStream(outPath.resolve("nbt.csv")), false, "UTF-8").use { nbtFile ->
            nbtFile.println("count,item,nbt")
            for ((key, count) in aggregated.entries.sortedBy { it.key.first }) {
                nbtFile.print(count)
                nbtFile.print(',')
                nbtFile.print(csv(key.first))
                nbtFile.print(',')
                nbtFile.println(csv(key.second))
            }
        }
    }
}
