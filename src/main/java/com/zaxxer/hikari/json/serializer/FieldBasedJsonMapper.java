package com.zaxxer.hikari.json.serializer;

import static com.zaxxer.hikari.json.util.Utf8Utils.fastTrackAsciiDecode;
import static com.zaxxer.hikari.json.util.Utf8Utils.findEndQuote;
import static com.zaxxer.hikari.json.util.Utf8Utils.findEndQuoteUTF8;
import static com.zaxxer.hikari.json.util.Utf8Utils.seekBackUtf8Boundary;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

import sun.misc.Unsafe;

import com.zaxxer.hikari.json.JsonFactory.Option;
import com.zaxxer.hikari.json.ObjectMapper;
import com.zaxxer.hikari.json.util.MutableBoolean;
import com.zaxxer.hikari.json.util.Phield;
import com.zaxxer.hikari.json.util.Types;
import com.zaxxer.hikari.json.util.UnsafeHelper;

@SuppressWarnings("restriction")
public final class FieldBasedJsonMapper implements ObjectMapper
{
   protected static final char CR = '\r';
   protected static final char TAB = '\t';
   protected static final char SPACE = ' ';
   protected static final char QUOTE = '"';
   protected static final char COLON = ':';
   protected static final char COMMA = ',';
   protected static final char NEWLINE = '\n';
   protected static final char OPEN_CURLY = '{';
   protected static final char CLOSE_CURLY = '}';
   protected static final char OPEN_BRACKET = '[';
   protected static final char CLOSE_BRACKET = ']';

   private static final Unsafe UNSAFE = UnsafeHelper.getUnsafe();
   private final boolean isAsciiMembers;
   private final boolean isAsciiValues;
   private final int BUFFER_SIZE = 16384;

   protected InputStream source;
   protected byte[] byteBuffer;
   protected int bufferLimit;

   public FieldBasedJsonMapper(Option... options) {
      byteBuffer = new byte[BUFFER_SIZE];

      HashSet<Option> set = new HashSet<>(Arrays.asList(options));
      isAsciiMembers = set.contains(Option.MEMBERS_ASCII);
      isAsciiValues = set.contains(Option.VALUES_ASCII);
   }

   @Override
   @SuppressWarnings("unchecked")
   final public <T> T readValue(final InputStream src, final Class<T> valueType)
   {
      source = src;

      ParseContext context = new ParseContext(valueType);
      context.createInstance();

      parseObject(0, context);
      return (T) context.target;
   }

   private int parseObject(int bufferIndex, final ParseContext context)
   {
      do {
         if (bufferIndex == bufferLimit && (bufferIndex = fillBuffer()) == -1) {
            throw new RuntimeException("Insufficent data.");
         }

         switch (byteBuffer[bufferIndex]) {
         case OPEN_CURLY:
            bufferIndex = parseMembers(bufferIndex + 1, context);
            continue;
         case CLOSE_CURLY:
            return bufferIndex + 1;
         default:
            bufferIndex++;
         }
      } while (true);
   }

   private int parseMembers(int bufferIndex, final ParseContext context)
   {
      int limit = bufferLimit;
      do {
         for (final byte[] buffer = byteBuffer; bufferIndex < limit && buffer[bufferIndex] <= SPACE; bufferIndex++)
            ; // skip whitespace

         if (bufferIndex == limit) {
            if ((bufferIndex = fillBuffer()) == -1) {
               throw new RuntimeException("Insufficent data.");
            }
            limit = bufferLimit;
         }

         switch (byteBuffer[bufferIndex]) {
         case QUOTE:
            bufferIndex = parseMember(bufferIndex, context);
            break;
         case CLOSE_CURLY:
            return bufferIndex;
         default:
            bufferIndex++;
         }
      } while (true);
   }

   private int parseMember(int bufferIndex, final ParseContext context)
   {
      // Parse the member name
      bufferIndex = (isAsciiMembers ? parseAsciiString(bufferIndex + 1, context) : parseString(bufferIndex + 1, context));

      // Next character better be a colon
      do {
         if (bufferIndex == bufferLimit && (bufferIndex = fillBuffer()) == -1) {
            throw new RuntimeException("Insufficent data.  Expecting colon after member.");
         }

         if (byteBuffer[bufferIndex++] == COLON) {
            break;
         }
      } while (true);

      // Now the value
      final Phield phield = context.clazz.getPhield(context.stringHolder);
      context.holderType = phield.type;
      if (phield.type == Types.OBJECT) {
         final ParseContext nextContext = new ParseContext(phield);
         nextContext.createInstance();
         context.objectHolder = nextContext.target;
         bufferIndex = parseValue(bufferIndex, context, nextContext);
      }
      else {
         bufferIndex = parseValue(bufferIndex, context, null);
      }

      setMember(phield, context);

      return bufferIndex;
   }

   private int parseValue(int bufferIndex, final ParseContext context, final ParseContext nextContext)
   {
      do {
         int limit = bufferLimit;
         while (bufferIndex < limit) {

            final int b = byteBuffer[bufferIndex++];
            if (b <= SPACE) {
               continue;
            }

            switch (b) {
            case QUOTE:
               return (isAsciiValues ? parseAsciiString(bufferIndex, context) : parseString(bufferIndex, context));
            case 't':
               context.booleanHolder = true;
               return bufferIndex;
            case 'f':
               context.booleanHolder = false;
               return bufferIndex;
            case 'n':
               context.objectHolder = null;
               return bufferIndex;
            case '-':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
               return ((context.holderType & Types.INTEGRAL_TYPE) > 0) ? parseInteger(bufferIndex - 1, context) : parseDecimal(bufferIndex - 1, context);
            case OPEN_CURLY:
               bufferIndex = parseMembers(bufferIndex, nextContext);
               break;
            case CLOSE_CURLY:
               return bufferIndex;
            case OPEN_BRACKET:
               return parseArray(bufferIndex, nextContext);
            }
         }

         if (bufferIndex == limit && ((bufferIndex = fillBuffer()) == -1)) {
            throw new RuntimeException("Insufficent data.");
         }
      } while (true);
   }

   private int parseArray(int bufferIndex, final ParseContext context)
   {
      int limit = bufferLimit;
      do {
         for (final byte[] buffer = byteBuffer; bufferIndex < limit && buffer[bufferIndex] <= SPACE; bufferIndex++)
            ; // skip whitespace

         if (bufferIndex == limit) {
            if ((bufferIndex = fillBuffer()) == -1) {
               throw new RuntimeException("Insufficent data.");
            }
            limit = bufferLimit;
         }

         switch (byteBuffer[bufferIndex]) {
         case CLOSE_BRACKET:
            return bufferIndex + 1;
         default:
            ParseContext nextContext = context;
            final Phield phield = context.phield;
            if (phield != null) {
               if (phield.isCollection || phield.isArray) {
                  nextContext = new ParseContext(phield.getCollectionParameterClazz1());
                  nextContext.createInstance();
               }
            }

            bufferIndex = parseValue(bufferIndex, context, nextContext);
            @SuppressWarnings("unchecked")
            Collection<Object> collection = ((Collection<Object>) context.target);
            collection.add(nextContext.target);
         }
      } while (true);
   }

   private int parseString(int bufferIndex, final ParseContext context)
   {
      try {
         final int startIndex = bufferIndex;
         do {
            final MutableBoolean utf8Detected = new MutableBoolean();
            final int newIndex = findEndQuoteUTF8(byteBuffer, bufferIndex, utf8Detected);
            if (newIndex > 0) {
               if (utf8Detected.bool) {
                  context.stringHolder = new String(byteBuffer, startIndex, (newIndex - startIndex), "UTF-8");
               }
               else {
                  context.stringHolder = fastTrackAsciiDecode(byteBuffer, startIndex, (newIndex - startIndex));
               }
               return newIndex + 1;
            }

            final byte[] newArray = new byte[bufferLimit * 2];
            System.arraycopy(byteBuffer, 0, newArray, 0, byteBuffer.length);
            byteBuffer = newArray;

            int read = source.read(byteBuffer, bufferIndex, byteBuffer.length - bufferIndex);
            if (read < 0) {
               throw new RuntimeException("Insufficent data.");
            }

            bufferIndex = seekBackUtf8Boundary(byteBuffer, bufferIndex);
         } while (true);
      }
      catch (Exception e) {
         throw new RuntimeException();
      }
   }

   private int parseAsciiString(int bufferIndex, final ParseContext context)
   {
      try {
         final int startIndex = bufferIndex;
         do {
            final int newIndex = findEndQuote(byteBuffer, bufferIndex);
            if (newIndex > 0) {
               context.stringHolder = fastTrackAsciiDecode(byteBuffer, startIndex, (newIndex - startIndex));
               return newIndex + 1;
            }

            final byte[] newArray = new byte[bufferLimit * 2];
            System.arraycopy(byteBuffer, 0, newArray, 0, byteBuffer.length);
            byteBuffer = newArray;

            int read = source.read(byteBuffer, bufferIndex, byteBuffer.length - bufferIndex);
            if (read < 0) {
               throw new RuntimeException("Insufficent data.");
            }

            bufferIndex = seekBackUtf8Boundary(byteBuffer, bufferIndex);
         } while (true);
      }
      catch (Exception e) {
         throw new RuntimeException();
      }
   }

   private int parseInteger(int bufferIndex, ParseContext context)
   {
      boolean neg = (byteBuffer[bufferIndex] == '-');
      if (neg) {
         ++bufferIndex;
      }

      int limit = bufferLimit;

      // integer part
      long part = 0;
      outer1: while (true) {
         try {
            for (final byte[] buffer = byteBuffer; bufferIndex < limit; bufferIndex++) {
               final int b = buffer[bufferIndex];
               if (b >= '0' && b <= '9') {
                  part = part * 10 + (b - '0');
               }
               else {
                  break outer1;
               }
            }
            limit = bufferLimit;
         }
         finally {
            if (bufferIndex == limit && ((bufferIndex = fillBuffer()) == -1)) {
               throw new RuntimeException("Insufficent data during number parsing.");
            }
         }
      }

      if (neg) {
         part *= -1;
      }

      context.longHolder = part;
      return bufferIndex;
   }

   private int parseDecimal(int bufferIndex, ParseContext context)
   {
      double d = 0.0;      // value
      long part  = 0;      // the current part (int, float and sci parts of the number)
      
      boolean neg = (byteBuffer[bufferIndex] == '-');
      if (neg) {
         ++bufferIndex;
      }

      // integer part
      long shift = 0;
      outer1: while (true) {
         final int limit = bufferLimit;
         try {
            for (final byte[] buffer = byteBuffer; bufferIndex < limit; bufferIndex++) {
               final int b = buffer[bufferIndex];
               if (b >= '0' && b <= '9') {
                  shift *= 10;
                  part = part * 10 + (b - '0');
               }
               else if (b == '.') {
                  shift = 1;
               }
               else {
                  break outer1;
               }
            }
         }
         finally {
            if (bufferIndex == limit && ((bufferIndex = fillBuffer()) == -1)) {
               throw new RuntimeException("Insufficent data during number parsing.");
            }
         }
      }

      if (neg) {
         part *= -1;
      }

      d = shift != 0 ? (double)part / (double)shift : part;

      // scientific part
      if (byteBuffer[bufferIndex] == 'e' || byteBuffer[bufferIndex] == 'E') {
         ++bufferIndex;
         part = 0;
         neg = byteBuffer[bufferIndex] == '-';
         bufferIndex = neg ? ++bufferIndex : bufferIndex;
         outer1: while (true) {
            final int limit = bufferLimit;
            for (final byte[] buffer = byteBuffer; bufferIndex < limit; bufferIndex++) {
               final int b = buffer[bufferIndex];
               if (b >= '0' && b <= '9') {
                  part = part * 10 + (b - '0');
                  continue;
               }

               break outer1;
            }

            if (bufferIndex == limit && ((bufferIndex = fillBuffer()) == -1)) {
               throw new RuntimeException("Insufficent data during number parsing.");
            }
         }

         d = (neg) ? d / (double)Math.pow(10, part) : d * (double)Math.pow(10, part);
      }

      context.doubleHolder = d;
      return bufferIndex;
   }

   private void setMember(final Phield phield, final ParseContext context)
   {
      try {
         switch (phield.type) {
         case Types.INT:
            UNSAFE.putInt(context.target, phield.fieldOffset, (int) context.longHolder);
            break;
         case Types.FLOAT:
            UNSAFE.putFloat(context.target, phield.fieldOffset, (float) context.doubleHolder);
            break;
         case Types.DOUBLE:
            UNSAFE.putDouble(context.target, phield.fieldOffset, context.doubleHolder);
            break;
         case Types.STRING:
            UNSAFE.putObject(context.target, phield.fieldOffset, context.stringHolder);
            break;
         case Types.OBJECT:
            UNSAFE.putObject(context.target, phield.fieldOffset, (context.objectHolder == Void.TYPE ? null : context.objectHolder));
            break;
         }
      }
      catch (SecurityException | IllegalArgumentException e) {
         throw new RuntimeException(e);
      }
   }

   final protected int fillBuffer()
   {
      try {
         int read = source.read(byteBuffer);
         if (read > 0) {
            bufferLimit = read;
            return 0;
         }

         return -1;
      }
      catch (IOException io) {
         throw new RuntimeException(io);
      }
   }
}