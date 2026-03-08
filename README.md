# link-hoarder
Scrape metadata, links, and guests from markdown. Intended for use in podcast production at Jupiter Broadcasting.

## Example Markdown

### Links Section
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

### Guests Section
```markdown
#### Guests
+ [John Smith](https://example.com/john)
  > John is a software engineer from Boston

+ [Jane Doe](https://example.com/jane)
  > Jane is a DevOps engineer specializing in Kubernetes

#### End Guests
```

Output:
```clojure
{:episode "111",
 :title "My Great Show",
 :description "this is an example episode",
 :tags "podcast, example, markdown",
 :links
 [{:href "https://example.com/example2",
   :title "a link",
   :quote "A quote or excerpt from the link"}],
 :guests
 [{:name "John Smith",
   :href "https://example.com/john",
   :role "guest",
   :bio "John is a software engineer from Boston"},
  {:name "Jane Doe",
   :href "https://example.com/jane",
   :role "guest",
   :bio "Jane is a DevOps engineer specializing in Kubernetes"}]}
```
