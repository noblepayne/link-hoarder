# link-hoarder
Scrape metadata and links from markdown. Intended for use in podcast production at Jupiter Broadcasting.

## Example Markdown
The following markdown document would produce
```markdown
# Some Markdown Document
#### Episode
111
#### Title
My Great Show
#### Description
this is an example episode
#### Tags
podcast, example, markdown

+ [not captured](https://example.com/example1)

#### Links
## Some Content
+ [a link](https://example.com/example2)
  > A quote or excerpt from the link

#### End Links
+ [also not captured](https://example.com/example)
```

this data:

```clojure
{:episode "111",
 :title "My Great Show",
 :description "this is an example episode",
 :tags "podcast, example, markdown",
 :links
 ({:href "https://example.com/example2",
   :title "a link",
   :quote "A quote or excerpt from the link"})}
```
