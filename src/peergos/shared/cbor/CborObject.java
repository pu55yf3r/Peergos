package peergos.shared.cbor;

import peergos.shared.ipfs.api.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.stream.*;

public interface CborObject {

    void serialize(CborEncoder encoder);

    default byte[] toByteArray() {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        CborEncoder encoder = new CborEncoder(bout);
        serialize(encoder);
        return bout.toByteArray();
    }

    int LINK_TAG = 258;

    static CborObject fromByteArray(byte[] cbor) {
        return deserialize(new CborDecoder(new ByteArrayInputStream(cbor)));
    }

    static CborObject deserialize(CborDecoder decoder) {
        try {
            CborType type = decoder.peekType();
            switch (type.getMajorType()) {
                case CborConstants.TYPE_TEXT_STRING:
                    return new CborString(decoder.readTextString());
                case CborConstants.TYPE_BYTE_STRING:
                    return new CborByteArray(decoder.readByteString());
                case CborConstants.TYPE_UNSIGNED_INTEGER:
                    return new CborLong(decoder.readInt());
                case CborConstants.TYPE_NEGATIVE_INTEGER:
                    return new CborLong(decoder.readInt());
                case CborConstants.TYPE_FLOAT_SIMPLE:
                    if (type.getAdditionalInfo() == CborConstants.NULL) {
                        decoder.readNull();
                        return new CborNull();
                    }
                    if (type.getAdditionalInfo() == CborConstants.TRUE) {
                        decoder.readBoolean();
                        return new CborBoolean(true);
                    }
                    if (type.getAdditionalInfo() == CborConstants.FALSE) {
                        decoder.readBoolean();
                        return new CborBoolean(false);
                    }
                    throw new IllegalStateException("Unimplemented simple type! " + type.getAdditionalInfo());
                case CborConstants.TYPE_MAP: {
                    long nValues = decoder.readMapLength();
                    SortedMap<CborObject, CborObject> result = new TreeMap<>();
                    for (long i=0; i < nValues; i++) {
                        CborObject key = deserialize(decoder);
                        CborObject value = deserialize(decoder);
                        result.put(key, value);
                    }
                    return new CborMap(result);
                }
                case CborConstants.TYPE_ARRAY:
                    long nItems = decoder.readArrayLength();
                    List<CborObject> res = new ArrayList<>((int) nItems);
                    for (long i=0; i < nItems; i++)
                        res.add(deserialize(decoder));
                    return new CborList(res);
                case CborConstants.TYPE_TAG:
                    long tag = decoder.readTag();
                    if (tag == LINK_TAG) {
                        CborObject value = deserialize(decoder);
                        if (value instanceof CborString)
                            return new CborMerkleLink(new MultiAddress(((CborString) value).value));
                        if (value instanceof CborByteArray)
                            return new CborMerkleLink(new MultiAddress(((CborByteArray) value).value));
                        throw new IllegalStateException("Invalid type for merkle link: " + value);
                    }
                    throw new IllegalStateException("Unknown TAG in CBOR: " + type.getAdditionalInfo());
                default:
                    throw new IllegalStateException("Unimplemented cbor type: " + type);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    final class CborMap implements CborObject {
        public final SortedMap<CborObject, CborObject> values;

        public CborMap(SortedMap<CborObject, CborObject> values) {
            this.values = values;
        }

        public static CborMap build(Map<String, CborObject> values) {
            SortedMap<CborObject, CborObject> transformed = values.entrySet()
                    .stream()
                    .collect(Collectors.toMap(
                            e -> new CborString(e.getKey()),
                            e -> e.getValue(),
                            (a, b) -> a, TreeMap::new));
            return new CborMap(transformed);
        }

        @Override
        public void serialize(CborEncoder encoder) {
            try {
                encoder.writeMapStart(values.size());
                for (Map.Entry<CborObject, CborObject> entry : values.entrySet()) {
                    entry.getKey().serialize(encoder);
                    entry.getValue().serialize(encoder);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CborMap cborMap = (CborMap) o;

            return values != null ? values.equals(cborMap.values) : cborMap.values == null;

        }

        @Override
        public int hashCode() {
            return values != null ? values.hashCode() : 0;
        }
    }

    final class CborMerkleLink implements CborObject {
        public final MultiAddress target;

        public CborMerkleLink(MultiAddress target) {
            this.target = target;
        }

        @Override
        public void serialize(CborEncoder encoder) {
            try {
                encoder.writeTag(LINK_TAG);
                if (isInvertible(target))
                    encoder.writeByteString(target.getBytes());
                else
                    encoder.writeTextString(target.toString());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // use binary encoding if addr string -> bytes -> addr string == identity
        private boolean isInvertible(MultiAddress addr) {
            return addr.toString().equals(new MultiAddress(addr.getBytes()).toString());
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CborMerkleLink that = (CborMerkleLink) o;

            return target != null ? target.equals(that.target) : that.target == null;

        }

        @Override
        public int hashCode() {
            return target != null ? target.hashCode() : 0;
        }
    }

    final class CborList implements CborObject {
        public final List<CborObject> value;

        public CborList(List<CborObject> value) {
            this.value = value;
        }

        @Override
        public void serialize(CborEncoder encoder) {
            try {
                encoder.writeArrayStart(value.size());
                for (CborObject object : value) {
                    object.serialize(encoder);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CborList cborList = (CborList) o;

            return value != null ? value.equals(cborList.value) : cborList.value == null;
        }

        @Override
        public int hashCode() {
            return value != null ? value.hashCode() : 0;
        }
    }

    final class CborBoolean implements CborObject {
        public final boolean value;

        public CborBoolean(boolean value) {
            this.value = value;
        }

        @Override
        public void serialize(CborEncoder encoder) {
            try {
                encoder.writeBoolean(value);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CborBoolean that = (CborBoolean) o;

            return value == that.value;

        }

        @Override
        public int hashCode() {
            return (value ? 1 : 0);
        }

        @Override
        public String toString() {
            return "CborBoolean{" +
                    value +
                    '}';
        }
    }

    final class CborByteArray implements CborObject, Comparable<CborByteArray> {
        public final byte[] value;

        public CborByteArray(byte[] value) {
            this.value = value;
        }

        @Override
        public int compareTo(CborByteArray other) {
            return ArrayOps.compare(value, other.value);
        }

        @Override
        public void serialize(CborEncoder encoder) {
            try {
                encoder.writeByteString(value);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CborByteArray that = (CborByteArray) o;

            return Arrays.equals(value, that.value);

        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }

    final class CborString implements CborObject, Comparable<CborString> {

        public final String value;

        public CborString(String value) {
            this.value = value;
        }

        @Override
        public int compareTo(CborString cborString) {
            return value.compareTo(cborString.value);
        }

        @Override
        public void serialize(CborEncoder encoder) {
            try {
                encoder.writeTextString(value);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CborString that = (CborString) o;

            return value.equals(that.value);

        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public String toString() {
            return "CborString{\"" +
                    value +
                    "\"}";
        }
    }

    final class CborLong implements CborObject, Comparable<CborLong> {
        public final long value;

        public CborLong(long value) {
            this.value = value;
        }

        @Override
        public int compareTo(CborLong other) {
            return Long.compare(value, other.value);
        }

        @Override
        public void serialize(CborEncoder encoder) {
            try {
                encoder.writeInt(value);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CborLong cborLong = (CborLong) o;

            return value == cborLong.value;

        }

        @Override
        public int hashCode() {
            return (int) (value ^ (value >>> 32));
        }

        @Override
        public String toString() {
            return "CborLong{" +
                    value +
                    '}';
        }
    }

    final class CborNull implements CborObject, Comparable<CborNull> {
        public CborNull() {}

        @Override
        public int compareTo(CborNull cborNull) {
            return 0;
        }

        @Override
        public void serialize(CborEncoder encoder) {
            try {
                encoder.writeNull();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return "CborNull{}";
        }
    }
}
