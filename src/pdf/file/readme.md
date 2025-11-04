# General PDF File Structure

ref: page 58

1. A one-line header identifying the version of the PDF specification to which the file conforms
1. A body containing the objects that make up the document contained in the file
1. A cross-reference table containing information about the indirect objects in the file
1. A trailer giving the location of the cross-reference table and of certain special objects within the body of the file

## Body
The body of a PDF file shall consist of a sequence of indirect objects representing the contents of a
document. The objects, which are of the basic types described in 7.3, "Objects" represent components of
the document such as fonts, pages, and sampled images. Beginning with PDF 1.5, the body can also
contain object streams, each of which contains a sequence of indirect objects; see 7.5.7, "Object
streams".


## Cross-reference table (xref table)
Essentially a table with a fixed format so that it can be easily parsed and provide context to a processor about memory locations of objects throughout the file. This provides random access to indirect objects and allows the parser to technically not be required to read the whole file to locate a particular thing

The table contains a one-line entry for each indirect object, specifying the byte offset of that object within the body of the file.

The cross reference section begins with the word `xref`. Then we add a line for each object entry.

Each entry shall be 20 bytes long, including the end of line marker.

There are two types of entries, one for objects in use `n` and one for objects that have been deleted and therefore are free `f`

The basic entry format is as such
```pdf
nnnnnnnnnn ggggg n eol
```

n = 10-digit byte offset in the decoded stream (padded with leading 0s)
g = 5-digit generation number (object number) (padded with leading 0s)
eol = 2-character end of line sequence. (SP CR, SP LF, CR LF)


*Basic Example 1*
_Shows 6 in-use objects with their positions in the document_
```pdf
0 6
0000000003 00000 n
0000000017 00000 n
0000000081 00000 n
0000000000 00000 n
0000000331 00000 n
0000000409 00000 n
```
TODO: Incremental updating of files...