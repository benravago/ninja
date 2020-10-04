/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package nashorn.internal.runtime;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import nashorn.internal.runtime.options.Options;

/**
 * Immutable hash map implementation for properties.
 *
 * Properties are keyed on strings or symbols (ES6).
 * Copying and cloning is avoided by relying on immutability.
 * <p>
 * When adding an element to a hash table, only the head of a bin list is updated, thus an add only requires the cloning of the bins array and adding an element to the head of the bin list.
 * Similarly for removal, only a portion of a bin list is updated.
 * <p>
 * For large tables with hundreds or thousands of elements, even just cloning the bins array when adding properties is an expensive operation.
 * For this case, we put new elements in a separate list called {@link ElementQueue}.
 * The list component is merged into the hash table at regular intervals during element insertion to keep it from growing too long.
 * Also, when a map with a queue component is queried repeatedly, the map will replace itself with a pure hash table version of itself to optimize lookup performance.
 * <p>
 * A separate chronological list is kept for quick generation of keys and values, and, for rehashing.
 * For very small maps where the overhead of the hash table would outweigh its benefits we deliberately avoid creating a hash structure and use the chronological list alone for element storage.
 * <p>
 * Details:
 * <p>
 * The main goal is to be able to retrieve properties from a map quickly, keying on the property name (String or Symbol).
 * A secondary, but important goal, is to keep maps immutable, so that a map can be shared by multiple objects in a context.
 * Sharing maps allows objects to be categorized as having similar properties, a fact that call site guards rely on.
 * In this discussion, immutability allows us to significantly reduce the amount of duplication we have in our maps.
 * <p>
 * The simplest of immutable maps is a basic singly linked list.
 * New properties are simply added to the head of the list.
 * Ancestor maps are not affected by the addition, since they continue to refer to their own head.
 * Searching is done by walking linearly though the elements until a match is found, O(N).
 * <p>
 * A hash map can be thought of as an optimization of a linked list map, where the linked list is broken into fragments based on hashCode(key).
 * An array is use
 * to quickly reference these fragments, indexing on hashCode(key) mod tableSize (tableSize is typically a power of 2 so that the mod is a fast masking operation).
 * If the size of the table is sufficient large, then search time approaches O(1).
 * In fact, most bins in a hash table are typically empty or contain a one element list.
 * <p>
 * For immutable hash maps, we can think of the hash map as an array of the shorter linked list maps.
 * If we add an element to the head of one of those lists, it doesn't affect any ancestor maps.
 * Thus adding an element to an immutable hash map only requires cloning the array and inserting an element at the head of one of the bins.
 * <p>
 * Using Java HashMaps we don't have enough control over the entries to allow us to implement this technique, so we are forced to clone the entire hash map.
 * <p>
 * Removing elements is done similarly.
 * We clone the array and then only modify the bin containing the removed element.
 * More often than not, the list contains only one element (or is very short), so this is not very costly.
 * When the list has several items, we need to clone the list portion prior to the removed item.
 * <p>
 * Another requirement of property maps is that we need to be able to gather all properties in chronological (add) order.
 * We have been using LinkedHashMap to provide this.
 * For the implementation of immutable hash map, we use a singly linked list that is linked in reverse chronological order.
 * This means we simply add new entries to the head of the list.
 * If we need to work with the list in forward order, it's simply a matter of allocating an array (size is known) and back filling in reverse order.
 * Removal of elements from the chronological list is trickier.
 * LinkedHashMap uses a doubly linked list to give constant time removal.
 * Immutable hash maps can't do that and maintain immutability.
 * So we manage the chronological list the same way we manage the bins, cloning up to the point of removal.
 * Don't panic; this cost is more than offset by the cost of cloning an entire LinkedHashMap.
 * Plus removal is far more rare than addition.
 * <p>
 * One more optimization: maps with a small number of entries don't use the hash map at all, the chronological list is used instead.
 * <p>
 * So the benefits from immutable arrays are; fewer objects and less copying.
 * For immutable hash map, when no removal is involved, the number of elements per property is two (bin + chronological elements).
 * For LinkedHashMap it is one (larger element) times the number of maps that refer to the property.
 * For immutable hash map, addition is constant time.
 * For LinkedHashMap it's O(N+C) since we have to clone the older map.
 */
public final class PropertyHashMap implements Map<Object, Property> {

    /** Number of initial bins. Power of 2. */
    private static final int INITIAL_BINS = 32;

    /** Threshold before using bins. */
    private static final int LIST_THRESHOLD = 8;

    /** Threshold before adding new elements to queue instead of directly adding to hash bins. */
    private static final int QUEUE_THRESHOLD = Options.getIntProperty("nashorn.propmap.queue.threshold", 500);

    /** Initial map. */
    public static final PropertyHashMap EMPTY_HASHMAP = new PropertyHashMap();

    /** Number of properties in the map. */
    private final int size;

    /** Threshold before growing the bins. */
    private final int threshold;

    /** Reverse list of all properties. */
    private final Element list;

    /** Hash map bins. */
    private Element[] bins;

    /** Queue for adding elements to large maps with delayed hashing. */
    private ElementQueue queue;

    /** All properties as an array (lazy). */
    private Property[] properties;

    /**
     * Empty map constructor.
     */
    private PropertyHashMap() {
        this.size = 0;
        this.threshold = 0;
        this.bins = null;
        this.queue = null;
        this.list = null;
    }

    /**
     * Constructor used internally to create new maps
     * @param map the new map
     */
    private PropertyHashMap(MapBuilder map) {
        this.size = map.size;
        if (map.qhead == null) {
            this.bins = map.bins;
            this.queue = null;
        } else {
            this.bins = null;
            this.queue = new ElementQueue(map.qhead, map.bins);
        }
        this.list = map.list;
        this.threshold = map.bins != null ? threeQuarters(map.bins.length) : 0;
    }

    /**
     * Clone a property map, replacing a property with a new one in the same place, which is important for property iterations if a property changes types
     * @param property    old property
     * @param newProperty new property
     * @return new property map
     */
    public PropertyHashMap immutableReplace(Property property, Property newProperty) {
        assert property.getKey().equals(newProperty.getKey()) : "replacing properties with different keys: '" + property.getKey() + "' != '" + newProperty.getKey() + "'";
        assert findElement(property.getKey()) != null : "replacing property that doesn't exist in map: '" + property.getKey() + "'";
        var builder = newMapBuilder(size);
        builder.replaceProperty(property.getKey(), newProperty);
        return new PropertyHashMap(builder);
    }

    /**
     * Clone a {@link PropertyHashMap} and add a {@link Property}.
     * @param property {@link Property} to add.
     * @return New {@link PropertyHashMap}.
     */
    public PropertyHashMap immutableAdd(Property property) {
        var newSize = size + 1;
        var builder = newMapBuilder(newSize);
        builder.addProperty(property);
        return new PropertyHashMap(builder);
    }

    /**
     * Clone a {@link PropertyHashMap} and add an array of properties.
     * @param newProperties Properties to add.
     * @return New {@link PropertyHashMap}.
     */
    public PropertyHashMap immutableAdd(Property... newProperties) {
        var newSize = size + newProperties.length;
        var builder = newMapBuilder(newSize);
        for (var property : newProperties) {
            builder.addProperty(property);
        }
        return new PropertyHashMap(builder);
    }

    /**
     * Clone a {@link PropertyHashMap} and add a collection of properties.
     * @param newProperties Properties to add.
     * @return New {@link PropertyHashMap}.
     */
    public PropertyHashMap immutableAdd(Collection<Property> newProperties) {
        if (newProperties != null) {
            var newSize = size + newProperties.size();
            var builder = newMapBuilder(newSize);
            for (var property : newProperties) {
                builder.addProperty(property);
            }
            return new PropertyHashMap(builder);
        }
        return this;
    }

    /**
     * Clone a {@link PropertyHashMap} and remove a {@link Property} based on its key.
     * @param key Key of {@link Property} to remove.
     * @return New {@link PropertyHashMap}.
     */
    public PropertyHashMap immutableRemove(Object key) {
        var builder = newMapBuilder(size);
        builder.removeProperty(key);
        if (builder.size < size) {
            return builder.size != 0 ? new PropertyHashMap(builder) : EMPTY_HASHMAP;
        }
        return this;
    }

    /**
     * Find a {@link Property} in the {@link PropertyHashMap}.
     * @param key Key of {@link Property} to find.
     * @return {@link Property} matching key or {@code null} if not found.
     */
    public Property find(Object key) {
        var element = findElement(key);
        return element != null ? element.getProperty() : null;
    }

    /**
     * Return an array of properties in chronological order of adding.
     */
    Property[] getProperties() {
        if (properties == null) {
            var array = new Property[size];
            var i = size;
            for (var element = list; element != null; element = element.getLink()) {
                array[--i] = element.getProperty();
            }
            properties = array;
        }
        return properties;
    }

    /**
     * Returns the bin index from the key.
     * @param bins     The bins array.
     * @param key      {@link Property} key.
     * @return The bin index.
     */
    private static int binIndex(Element[] bins, Object key) {
        return key.hashCode() & bins.length - 1;
    }

    /**
     * Calculate the number of bins needed to contain n properties.
     * @param n Number of elements.
     * @return Number of bins required.
     */
    private static int binsNeeded(int n) {
        // 50% padding
        return 1 << 32 - Integer.numberOfLeadingZeros(n + (n >>> 1) | INITIAL_BINS - 1);
    }

    /**
     * Used to calculate the current capacity of the bins.
     * @param n Number of bin slots.
     * @return 75% of n.
     */
    private static int threeQuarters(int n) {
        return (n >>> 1) + (n >>> 2);
    }

    /**
     * Regenerate the bin table after changing the number of bins.
     * @param list    // List of all properties.
     * @param binSize // New size of bins.
     * @return Populated bins.
     */
    private static Element[] rehash(Element list, int binSize) {
        var newBins = new Element[binSize];
        for (var element = list; element != null; element = element.getLink()) {
            var property = element.getProperty();
            var key = property.getKey();
            var binIndex = binIndex(newBins, key);

            newBins[binIndex] = new Element(newBins[binIndex], property);
        }
        return newBins;
    }

    /**
     * Locate an element based on key.
     * @param key {@link Element} key.
     * @return {@link Element} matching key or {@code null} if not found.
     */
    private Element findElement(Object key) {
        if (queue != null) {
            return queue.find(key);
        } else if (bins != null) {
            var binIndex = binIndex(bins, key);
            return findElement(bins[binIndex], key);
        }
        return findElement(list, key);
    }

    /**
     * Locate an {@link Element} based on key from a specific list.
     * @param elementList Head of {@link Element} list
     * @param key         {@link Element} key.
     * @return {@link Element} matching key or {@code null} if not found.
     */
    private static Element findElement(Element elementList, Object key) {
        var hashCode = key.hashCode();
        for (var element = elementList; element != null; element = element.getLink()) {
            if (element.match(key, hashCode)) {
                return element;
            }
        }
        return null;
    }

    /**
     * Create a {@code MapBuilder} to add new elements to.
     * @param newSize New size of {@link PropertyHashMap}.
     * @return {@link MapBuilder} for the new size.
     */
    private MapBuilder newMapBuilder(int newSize) {
        if (bins == null && newSize < LIST_THRESHOLD) {
            return new MapBuilder(bins, list, size, false);
        } else if (newSize > threshold) {
            return new MapBuilder(rehash(list, binsNeeded(newSize)), list, size, true);
        } else if (shouldCloneBins(size, newSize)) {
            return new MapBuilder(cloneBins(), list, size, true);
        } else if (queue == null) {
            return new MapBuilder(bins, list, size, false);
        } else {
            return new MapBuilder(queue, list, size, false);
        }
    }

    /**
     * Create a cloned or new bins array and merge the elements in the queue into it if there are any.
     * @return the cloned bins array
     */
    private Element[] cloneBins() {
        if (queue != null) {
            return queue.cloneAndMergeBins();
        }

        return bins.clone();
    }

    /**
     * Used on insertion to determine whether the bins array should be cloned, or we should keep using the ancestor's bins array and put new elements into the queue.
     * @param oldSize the old map size
     * @param newSize the new map size
     * @return whether to clone the bins array
     */
    private boolean shouldCloneBins(int oldSize, int newSize) {
        // For maps with less than QUEUE_THRESHOLD elements we clone the bins array on every insertion.
        // Above that threshold we put new elements into the queue and only merge every 512  elements.
        return newSize < QUEUE_THRESHOLD || (newSize >>> 9) > (oldSize >>> 9);
    }

    /**
     * Removes an {@link Element} from a specific list, avoiding duplication.
     * @param list List to remove from.
     * @param key  Key of {@link Element} to remove.
     * @return New list with {@link Element} removed.
     */
    private static Element removeFromList(Element list, Object key) {
        if (list == null) {
            return null;
        }
        var hashCode = key.hashCode();
        if (list.match(key, hashCode)) {
            return list.getLink();
        }
        var head = new Element(null, list.getProperty());
        var previous = head;
        for (var element = list.getLink(); element != null; element = element.getLink()) {
            if (element.match(key, hashCode)) {
                previous.setLink(element.getLink());
                return head;
            }
            var next = new Element(null, element.getProperty());
            previous.setLink(next);
            previous = next;
        }
        return list;
    }

    // for element x. if x get link matches,
    private static Element replaceInList(Element list, Object key, Property property) {
        assert list != null;
        var hashCode = key.hashCode();

        if (list.match(key, hashCode)) {
            return new Element(list.getLink(), property);
        }

        var head = new Element(null, list.getProperty());
        var previous = head;
        for (var element = list.getLink(); element != null; element = element.getLink()) {
            if (element.match(key, hashCode)) {
                previous.setLink(new Element(element.getLink(), property));
                return head;
            }
            var next = new Element(null, element.getProperty());
            previous.setLink(next);
            previous = next;
        }
        return list;
    }

    /*
     * Map implementation
     */

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        assert key instanceof String || key instanceof Symbol;
        return findElement(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        if (value instanceof Property) {
            var property = (Property) value;
            var element = findElement(property.getKey());
            return element != null && element.getProperty().equals(value);
        }
        return false;
    }

    @Override
    public Property get(Object key) {
        assert key instanceof String || key instanceof Symbol;
        var element = findElement(key);
        return element != null ? element.getProperty() : null;
    }

    @Override
    public Property put(Object key, Property value) {
        throw new UnsupportedOperationException("Immutable map.");
    }

    @Override
    public Property remove(Object key) {
        throw new UnsupportedOperationException("Immutable map.");
    }

    @Override
    public void putAll(Map<? extends Object, ? extends Property> m) {
        throw new UnsupportedOperationException("Immutable map.");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("Immutable map.");
    }

    @Override
    public Set<Object> keySet() {
        var set = new HashSet<Object>();
        for (var element = list; element != null; element = element.getLink()) {
            set.add(element.getKey());
        }
        return Collections.unmodifiableSet(set);
    }

    @Override
    public Collection<Property> values() {
        return Collections.unmodifiableList(Arrays.asList(getProperties()));
    }

    @Override
    public Set<Entry<Object, Property>> entrySet() {
        var set = new HashSet<Entry<Object, Property>>();
        for (var element = list; element != null; element = element.getLink()) {
            set.add(element);
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * List map element.
     */
    static final class Element implements Entry<Object, Property> {

        /** Link for list construction. */
        private Element link;

        /** Element property. */
        private final Property property;

        /** Element key. Kept separate for performance.) */
        private final Object key;

        /** Element key hash code. */
        private final int hashCode;

        /*
         * Constructors
         */

        Element(Element link, Property property) {
            this.link = link;
            this.property = property;
            this.key = property.getKey();
            this.hashCode = this.key.hashCode();
        }

        boolean match(Object otherKey, int otherHashCode) {
            return this.hashCode == otherHashCode && this.key.equals(otherKey);
        }

        /*
         * Entry implmentation.
         */

        @Override
        public boolean equals(Object other) {
            assert property != null && other != null;
            return other instanceof Element && property.equals(((Element)other).property);
        }

        @Override
        public Object getKey() {
            return key;
        }

        @Override
        public Property getValue() {
            return property;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public Property setValue(Property value) {
            throw new UnsupportedOperationException("Immutable map.");
        }

        @Override
        public String toString() {
            var sb = new StringBuffer();

            sb.append('[');

            var elem = this;
            do {
                sb.append(elem.getValue());
                elem = elem.link;
                if (elem != null) {
                    sb.append(" -> ");
                }
            } while (elem != null);

            sb.append(']');

            return sb.toString();
        }

        /*
         * Accessors
         */

        Element getLink() {
            return link;
        }

        void setLink(Element link) {
            this.link = link;
        }

        Property getProperty() {
            return property;
        }
    }

    /**
     * A hybrid map/list structure to add elements to the map without the need to clone and rehash the main table.
     * This is used for large maps to reduce the overhead of adding elements.
     * Instances of this class can replace themselves with a pure hash map version of themselves to optimize query performance.
     */
    private class ElementQueue {

        /** List of elements not merged into bins */
        private final Element qhead;

        /** Our own bins array. Differs from original PropertyHashMap bins when queue is merged. */
        private final Element[] qbins;

        /** Count searches to trigger merging of queue into bins. */
        int searchCount = 0;

        ElementQueue(Element qhead, Element[] qbins) {
            this.qhead = qhead;
            this.qbins = qbins;
        }

        Element find(Object key) {
            var binIndex = binIndex(qbins, key);
            var element = findElement(qbins[binIndex], key);
            if (element != null) {
                return element;
            }
            if (qhead != null) {
                if (++searchCount > 2) {
                    // Merge the queue into the hash bins if this map is queried more than a few times
                    var newBins = cloneAndMergeBins();
                    assert newBins != qbins;
                    PropertyHashMap.this.queue = new ElementQueue(null, newBins);
                    return PropertyHashMap.this.queue.find(key);
                }
                return findElement(qhead, key);
            }
            return null;
        }

        /**
         * Create a cloned or new bins array and merge the elements in the queue into it if there are any.
         * @return the cloned bins array
         */
        private Element[] cloneAndMergeBins() {
            if (qhead == null) {
                return qbins;
            }

            var newBins = qbins.clone();

            for (var element = qhead; element != null; element = element.getLink()) {
                var property = element.getProperty();
                var key = property.getKey();
                var binIndex = binIndex(newBins, key);

                newBins[binIndex] = new Element(newBins[binIndex], property);
            }

            return newBins;
        }

    }

    /**
     * A builder class used for adding, replacing, or removing elements.
     */
    private static class MapBuilder {

        /** Bins array - may be shared with map that created us. */
        private Element[] bins;

        /** Whether our bins are shared */
        private boolean hasOwnBins;

        /** Queue of unmerged elements */
        private Element qhead;

        /** Full property list. */
        private Element list;

        /** Number of properties. */
        private int size;

        MapBuilder(Element[] bins, Element list, int size, boolean hasOwnBins) {
            this.bins = bins;
            this.hasOwnBins = hasOwnBins;
            this.list = list;
            this.qhead = null;
            this.size = size;
        }

        MapBuilder(ElementQueue queue, Element list, int size, boolean hasOwnBins) {
            this.bins = queue.qbins;
            this.hasOwnBins = hasOwnBins;
            this.list = list;
            this.qhead = queue.qhead;
            this.size = size;
        }

        /**
         * Add a {@link Property}.
         * Removes duplicates if necessary.
         * @param property {@link Property} to add.
         */
        private void addProperty(Property property) {
            var key = property.getKey();

            if (bins != null) {
                var binIndex = binIndex(bins, key);
                if (findElement(bins[binIndex], key) != null) {
                    ensureOwnBins();
                    bins[binIndex] = removeExistingElement(bins[binIndex], key);
                } else if (findElement(qhead, key) != null) {
                    qhead = removeExistingElement(qhead, key);
                }
                if (hasOwnBins) {
                    bins[binIndex] = new Element(bins[binIndex], property);
                } else {
                    qhead = new Element(qhead, property);
                }
            } else {
                if (findElement(list, key) != null) {
                    list = removeFromList(list, key);
                    size--;
                }
            }

            list = new Element(list, property);
            size++;
        }

        /**
         * Replace an existing {@link Property} with a new one with the same key.
         * @param key the property key
         * @param property the property to replace the old one with
         */
        private void replaceProperty(Object key, Property property) {

            if (bins != null) {
                var binIndex = binIndex(bins, key);
                var bin = bins[binIndex];
                if (findElement(bin, key) != null) {
                    ensureOwnBins();
                    bins[binIndex] = replaceInList(bin, key, property);
                } else if (qhead != null) {
                    qhead = replaceInList(qhead, key, property);
                }
            }

            list = replaceInList(list, key, property);
        }

        /**
         * Remove a {@link Property} based on its key.
         * @param key Key of {@link Property} to remove.
         */
        void removeProperty(Object key) {

            if (bins != null) {
                var binIndex = binIndex(bins, key);
                var bin = bins[binIndex];
                if (findElement(bin, key) != null) { ;
                    if (size >= LIST_THRESHOLD) {
                        ensureOwnBins();
                        bins[binIndex] = removeFromList(bin, key);
                    } else {
                        // Go back to list-only representation for small maps
                        bins = null;
                        qhead = null;
                    }
                } else if (findElement(qhead, key) != null) {
                    qhead = removeFromList(qhead, key);
                }
            }

            list = removeFromList(list, key);
            size--;
        }

        /**
         * Removes an element known to exist from an element list and the main {@code list} and decreases {@code size}.
         * @param element the element list
         * @param key the key to remove
         * @return the new element list
         */
        private Element removeExistingElement(Element element, Object key) {
            size--;
            list = removeFromList(list, key);
            return removeFromList(element, key);
        }

        /**
         * Make sure we own the bins we have, cloning them if necessary.
         */
        private void ensureOwnBins() {
            if (!hasOwnBins) {
                bins = bins.clone();
            }
            hasOwnBins = true;
        }
    }

}
