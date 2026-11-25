//! A small DOM over quick-xml, as strict as the DocumentBuilder IbkrFlexClient configures: a
//! DOCTYPE is refused, so no entity can be declared, let alone an external one; only the
//! predefined entities and character references expand; the document must be well-formed.

use quick_xml::XmlVersion;
use quick_xml::events::{BytesRef, BytesStart, Event};
use quick_xml::reader::Reader;

/// The payload is not a well-formed XML document (or declares a DOCTYPE).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Malformed;

#[derive(Debug, Clone, PartialEq, Eq)]
enum Node {
    Element(Element),
    Text(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Element {
    /// The qualified name, as a namespace-unaware DOM reports it.
    pub name: String,
    attributes: Vec<(String, String)>,
    children: Vec<Node>,
}

impl Element {
    fn start(event: &BytesStart<'_>) -> Result<Self, Malformed> {
        let mut attributes = Vec::new();
        for attribute in event.attributes() {
            let attribute = attribute.map_err(|_| Malformed)?;
            let value = attribute
                .normalized_value(XmlVersion::Implicit1_0)
                .map_err(|_| Malformed)?;
            attributes.push((attribute.key.as_ref().to_owned(), value.into_owned()));
        }
        Ok(Self {
            name: event.name().as_ref().to_owned(),
            attributes,
            children: Vec::new(),
        })
    }

    /// Element.getAttribute when hasAttribute, None otherwise.
    pub fn attribute(&self, name: &str) -> Option<&str> {
        self.attributes
            .iter()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value.as_str())
    }

    /// Element.getElementsByTagName: every descendant with that name, in document order.
    pub fn descendants(&self, tag: &str) -> Vec<&Element> {
        let mut found = Vec::new();
        self.collect(tag, &mut found);
        found
    }

    /// Document.getElementsByTagName: the descendants, with this (root) element first if it
    /// carries the name itself.
    pub fn self_and_descendants(&self, tag: &str) -> Vec<&Element> {
        let mut found = Vec::new();
        if self.name == tag {
            found.push(self);
        }
        self.collect(tag, &mut found);
        found
    }

    fn collect<'a>(&'a self, tag: &str, found: &mut Vec<&'a Element>) {
        for child in &self.children {
            if let Node::Element(element) = child {
                if element.name == tag {
                    found.push(element);
                }
                element.collect(tag, found);
            }
        }
    }

    /// Node.getTextContent: every descendant text, concatenated.
    pub fn text_content(&self) -> String {
        let mut text = String::new();
        self.append_text(&mut text);
        text
    }

    fn append_text(&self, out: &mut String) {
        for child in &self.children {
            match child {
                Node::Text(text) => out.push_str(text),
                Node::Element(element) => element.append_text(out),
            }
        }
    }

    fn push_text(&mut self, text: &str) {
        if let Some(Node::Text(last)) = self.children.last_mut() {
            last.push_str(text);
        } else {
            self.children.push(Node::Text(text.to_owned()));
        }
    }
}

fn is_xml_whitespace(text: &str) -> bool {
    text.chars().all(|c| matches!(c, ' ' | '\t' | '\r' | '\n'))
}

fn resolve_reference(reference: &BytesRef<'_>) -> Result<String, Malformed> {
    if let Some(c) = reference.resolve_char_ref().map_err(|_| Malformed)? {
        return Ok(c.to_string());
    }
    let replacement = match reference.as_ref() {
        "lt" => "<",
        "gt" => ">",
        "amp" => "&",
        "apos" => "'",
        "quot" => "\"",
        // An undeclared entity is a fatal error without a DTD, and a DTD is refused.
        _ => return Err(Malformed),
    };
    Ok(replacement.to_owned())
}

/// Parses a whole document into its root element.
pub fn parse(xml: &str) -> Result<Element, Malformed> {
    let xml = xml.strip_prefix('\u{feff}').unwrap_or(xml);
    let mut reader = Reader::from_str(xml);
    let mut open: Vec<Element> = Vec::new();
    let mut root: Option<Element> = None;

    fn close(
        element: Element,
        open: &mut [Element],
        root: &mut Option<Element>,
    ) -> Result<(), Malformed> {
        match open.last_mut() {
            Some(parent) => parent.children.push(Node::Element(element)),
            None if root.is_none() => *root = Some(element),
            None => return Err(Malformed),
        }
        Ok(())
    }

    loop {
        match reader.read_event().map_err(|_| Malformed)? {
            Event::Start(start) => {
                if open.is_empty() && root.is_some() {
                    return Err(Malformed);
                }
                open.push(Element::start(&start)?);
            }
            Event::Empty(start) => {
                if open.is_empty() && root.is_some() {
                    return Err(Malformed);
                }
                close(Element::start(&start)?, &mut open, &mut root)?;
            }
            Event::End(_) => {
                let element = open.pop().ok_or(Malformed)?;
                close(element, &mut open, &mut root)?;
            }
            Event::Text(text) => {
                let content = text.xml10_content();
                match open.last_mut() {
                    Some(parent) => parent.push_text(&content),
                    None if is_xml_whitespace(&content) => {}
                    None => return Err(Malformed),
                }
            }
            Event::CData(data) => match open.last_mut() {
                Some(parent) => parent.push_text(&data.xml10_content()),
                None => return Err(Malformed),
            },
            Event::GeneralRef(reference) => match open.last_mut() {
                Some(parent) => parent.push_text(&resolve_reference(&reference)?),
                None => return Err(Malformed),
            },
            Event::DocType(_) => return Err(Malformed),
            Event::Decl(_) | Event::PI(_) | Event::Comment(_) => {}
            Event::Eof => break,
        }
    }
    if !open.is_empty() {
        return Err(Malformed);
    }
    root.ok_or(Malformed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_attributes_text_and_entities() {
        let root = parse(
            "<?xml version=\"1.0\"?><R a=\" x &amp; y \"><S>Su&#99;cess <![CDATA[<ok>]]></S><S/></R>",
        )
        .expect("well-formed");
        assert_eq!(root.name, "R");
        assert_eq!(root.attribute("a"), Some(" x & y "));
        assert_eq!(root.attribute("b"), None);
        assert_eq!(root.descendants("S").len(), 2);
        assert_eq!(root.descendants("S")[0].text_content(), "Success <ok>");
    }

    #[test]
    fn refuses_doctype_unknown_entities_and_broken_documents() {
        for bad in [
            "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>",
            "<r>&nbsp;</r>",
            "<r><a></r>",
            "<r>",
            "",
            "not xml",
            "<r/><r/>",
            "<r a=\"1\" a=\"2\"/>",
            "<html><body>Bad gateway<br></body></html>",
        ] {
            assert_eq!(parse(bad), Err(Malformed), "{bad}");
        }
    }
}
