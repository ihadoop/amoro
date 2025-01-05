package demo;

import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;

public class Compaction {
    public static void main(String[] args) {
        Catalog catalog = null;
        Table table = catalog.loadTable(TableIdentifier.of(""));


    }
}
