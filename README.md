# mc-scanner

Scan Minecraft worlds for certain blocks/items or
collect statistics about inventory contents

## Compiling
```shell
> ./gradlew build 
```

## Example Usage

### Searching for Sponges
```shell
> java -jar mc-scanner-<version>.jar -i sponge -i wet_sponge -b sponge -b wet_sponge <world directory> sponges.zip
# ... 20s later ...
# 2671/2671 5.9GiB/5.9GiB 298.6MiB/s 30 results 
```

### Collecting Statistics
Into a directory:
```shell
> java -jar mc-scanner-<version.jar> --stats <world directory> result/
```

Into a zip file:
```shell
> java -jar mc-scanner-<version.jar> --stats <world directory> result.zip
```

Of only one region file:
```shell
> java -jar mc-scanner-<version>.jar --stats <world>/region/r.92.-83.mca quarry-items.zip
# 1/1 10.4MiB/10.4MiB 11.1MiB/s 51224 results
```

### Exporting Items for Migration
Into a directory:
```shell
> java -jar mc-scanner-<version>.jar --export <world directory> result/
```

The export writes `export.litematic`, `export.nbt`, `manifest.csv`, and `totals.csv`.
Player inventories and ender chests are mirrored as-is into a labelled player grid;
all other items are compacted into identity, bulk, remainder, and unstackable grids.
Operator/command-only items are excluded from the export but remain in the scanned totals.
See `.scratch/export/spec.md` for the full layout and manifest rules.

`totals.csv` reports `Type,Scanned,Exported,Dropped`, where `Scanned` is the raw
count of every item found, including empty boxes and excluded items.