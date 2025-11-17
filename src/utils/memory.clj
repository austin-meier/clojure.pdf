(ns memory)


;; https://adobe-type-tools.github.io/font-tech-notes/pdfs/5176.CFF.pdf

(def ttf-types
  {:uint8	:u8
   :int8	:i8
   :uint16	:u16
   :int16	:i16
   :uint24	:u24
   :uint32	:u32
   :int32	:i32
   :Fixed	:i32 ;;32-bit signed fixed-point number (16.16)
   :FWORD	:i16 ;;int16 that describes a quantity in font design units.
   :UFWORD	:u16 ;;uint16 that describes a quantity in font design units.
   :F2DOT14	:i16 ;;16-bit signed fixed number with the low 14 bits of fraction (2.14).
   :LONGDATETIME	:i64 ;;Date and time represented in number of seconds since 12:00 midnight, January 1, 1904, UTC. The value is represented as a signed 64-bit integer.
   :Tag [:uint8, :uint8, :uint8, :uint8] ;;	Array of four uint8s (length = 32 bits) used to identify a table, design-variation axis, script, language system, feature, or baseline.
   :Offset8	:uint8 ;;8-bit offset to a table, same as uint8, NULL offset = 0x00
   :Offset16	:uint16 ;;Short offset to a table, same as uint16, NULL offset = 0x0000
   :Offset24	:uint24 ;;24-bit offset to a table, same as uint24, NULL offset = 0x000000
   :Offset32	:uint32 ;;Long offset to a table, same as uint32, NULL offset = 0x00000000
   :Version16Dot16 :u32 ;;Packed 32-bit value with major and minor version numbers.
   })

(defn )

(defn resolve-types
  [type-map]
  (reduce-kv
   (fn [m k v]
     (cond
       (primitive-type? v) (assoc m k v)
       (vector? v) (assoc m k (mapv type-map v))
       :else (assoc m k (type-map v))))
   {}
   type-map))

(comment
  (resolve-types ttf-types)

  )