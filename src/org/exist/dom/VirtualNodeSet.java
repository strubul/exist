
/* eXist Open Source Native XML Database
 * Copyright (C) 2001-06,  Wolfgang M. Meier (meier@ifs.tu-darmstadt.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * $Id$
 * 
 */
package org.exist.dom;

import java.util.Iterator;

import org.exist.xquery.Constants;
import org.exist.xquery.Expression;
import org.exist.xquery.NodeTest;
import org.exist.xquery.value.Item;
import org.exist.xquery.value.SequenceIterator;
import org.exist.xquery.value.Type;
import org.exist.numbering.NodeId;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * This node set is called virtual because it is just a placeholder for
 * the set of relevant nodes. For XPath expressions like //* or //node(), 
 * it would be totally unefficient to actually retrieve all descendant nodes.
 * In many cases, the expression can be resolved at a later point in time
 * without retrieving the whole node set. 
 *
 * VirtualNodeSet basically provides method getFirstParent to retrieve the first
 * matching descendant of its context according to the primary type axis.
 *
 * Class LocationStep will always return an instance of VirtualNodeSet
 * if it finds something like descendant::* etc..
 *
 * @author Wolfgang Meier
 * @author Timo Boehme
 */
public class VirtualNodeSet extends AbstractNodeSet {

	protected int axis = Constants.UNKNOWN_AXIS;
	protected NodeTest test;
	protected NodeSet context;
	protected NodeSet realSet = null;
	protected boolean realSetIsComplete = false;
	protected boolean inPredicate = false;
	protected boolean useSelfAsContext = false;
	protected int contextId = Expression.NO_CONTEXT_ID;

	public VirtualNodeSet(int axis, NodeTest test, int contextId, NodeSet context) {
		this.axis = axis;
		this.test = test;
		this.context = context;
        this.contextId = contextId;
	}
	
	public void setNodeTest(NodeTest test) {
		this.test = test;
	}

	public boolean contains(NodeProxy p) {
		NodeProxy first = getFirstParent(p, null, (axis == Constants.SELF_AXIS), 0);
		// Timo Boehme: getFirstParent returns now only real parents
		//              therefore test if node is child of context
        if (first != null)
            return true;
		if (context.get(p.getDocument(), p.getNodeId().getParentId()) != null)
            return true;
        return false;           
	}

	public void setInPredicate(boolean predicate) {
		inPredicate = predicate;
	}

	/* (non-Javadoc)
	 * @see org.exist.dom.AbstractNodeSet#getDocumentSet()
	 */
	public DocumentSet getDocumentSet() {
	    return context.getDocumentSet();
	}

    private NodeProxy getFirstParent(NodeProxy node, NodeProxy first, 
            boolean includeSelf, int recursions) {
        return getFirstParent(node, first, includeSelf, true, recursions);
    }

	private NodeProxy getFirstParent(NodeProxy node, NodeProxy first,
            boolean includeSelf, boolean directParent, int recursions) {
        
        NodeId pid = node.getNodeId().getParentId();
        
        /* if the node has no parent, i.e. pid == -1, we still need to complete this method
         * to check if we have found a potential parent in one of the iterations before.
         */
		NodeProxy parent;
		// check if the start-node should be included, e.g. to process an
		// expression like *[. = 'xxx']
		if (recursions == 0 && includeSelf && test.matches(node)) {
			if (axis == Constants.CHILD_AXIS) {
				// if we're on the child axis, test if
				// the node is a direct child of the context node
                parent = context.get(node.getDocument(), pid);
				if (parent != null) {
					node.copyContext(parent);
					if (useSelfAsContext && inPredicate) {
						node.addContextNode(contextId, node);
					} else if (inPredicate)
						node.addContextNode(contextId, parent);
					return node;
				}
			} else
				// descendant axis: remember the node and continue 
				first = node;
		}
        
		// if this is the first call to this method, remember the first parent node
		// and re-evaluate the method. We can't just return the first parent as
        // we need a parent that is actually contained in the context set. We thus
        // call the method again to complete.
        if (first == null) {
            if (pid == NodeId.DOCUMENT_NODE) {
                // given node was already document element -> no parent
                return null;
            }
            first = new NodeProxy(node.getDocument(), pid, Node.ELEMENT_NODE, NodeProxy.UNKNOWN_NODE_ADDRESS);
            if (test.getType() == Type.ATTRIBUTE)
            	return first;
            // Timo Boehme: we need a real parent (child from context)
            return getFirstParent(first, first, false, directParent, recursions + 1);
        }
        
		// is pid member of the context set?
		parent = context.get(node.getDocument(), pid);

		if (parent != null && test.matches(node)) {
			if (axis != Constants.CHILD_AXIS) {
				// if we are on the descendant-axis, we return the first node 
				// we found while walking bottom-up.
				// Otherwise, we return the last one (which is node)
				node = first;
			}
			node.copyContext(parent);
			if (useSelfAsContext && inPredicate) {
				node.addContextNode(contextId, node);
			} else if (inPredicate) {
				node.addContextNode(contextId, parent);
			}
			// Timo Boehme: we return the ancestor which is child of context			
			return node;
        } else if (pid == NodeId.DOCUMENT_NODE) {
            // no matching node has been found in the context
            return null;
		} else if (directParent && axis == Constants.CHILD_AXIS && recursions == 1) {
			// break here if the expression is like /*/n          
			return null;        
		} else {
			// continue for expressions like //*/n or /*//n
			parent = new NodeProxy(node.getDocument(), pid, Node.ELEMENT_NODE, NodeProxy.UNKNOWN_NODE_ADDRESS);
            return getFirstParent(parent, first, false, false, recursions + 1);
		}
	}

	private void addInternal(NodeProxy p) {
		if (realSet == null)
			realSet = new ExtArrayNodeSet(256);
		realSet.add(p);
		realSetIsComplete = false;
	}

	public NodeProxy parentWithChild(NodeProxy proxy, boolean directParent, boolean includeSelf,
            int level) {
		NodeProxy first = getFirstParent(proxy, null, includeSelf, directParent, 0);
		if (first != null)
			addInternal(first);
		return first;
	}

    public NodeProxy parentWithChild(DocumentImpl doc, NodeId nodeId, boolean directParent, boolean includeSelf) {
    	NodeProxy first = getFirstParent(new NodeProxy(doc, nodeId), null, includeSelf, directParent, 0);
		if (first != null)
			addInternal(first);
		return first;
    }

    private final NodeSet getNodes() {
		ExtArrayNodeSet result = new ExtArrayNodeSet();
		NodeProxy proxy;
		Iterator domIter;
		for (Iterator i = context.iterator(); i.hasNext();) {
			proxy = (NodeProxy) i.next();            
			if (proxy.getNodeId() == NodeId.DOCUMENT_NODE) {
				if(proxy.getDocument().getResourceType() == DocumentImpl.BINARY_FILE)
					// skip binary resources
					continue;
				NodeList cl = proxy.getDocument().getChildNodes();
                for (int j = 0; j < cl.getLength(); j++) {
                    StoredNode node = (StoredNode) cl.item(j);
    				NodeProxy docElemProxy =
    					new NodeProxy(proxy.getDocument(), node.getNodeId(), node.getNodeType());
    				docElemProxy.setInternalAddress(node.getInternalAddress());
    				if (test.matches(docElemProxy)) {
    					docElemProxy.deepCopyContext(proxy);
						if (useSelfAsContext && inPredicate) {
							docElemProxy.addContextNode(contextId, docElemProxy);
						}
                        result.add(docElemProxy);
    				}
    				if (node.getNodeType() == Node.ELEMENT_NODE &&
                        (axis == Constants.DESCENDANT_AXIS
    					|| axis == Constants.DESCENDANT_SELF_AXIS
    					|| axis == Constants.DESCENDANT_ATTRIBUTE_AXIS)) {
                        // note: we create a copy of the docElemProxy here to be used
                        // as context when traversing the tree.
                        NodeProxy contextNode = new NodeProxy(docElemProxy);
                        contextNode.deepCopyContext(proxy);
    					domIter = contextNode.getDocument().getBroker().getNodeIterator(contextNode);
                        domIter.next();
    					contextNode.setMatches(proxy.getMatches());
    					addChildren(contextNode, result, node, domIter, 0);
    				}
                }
				continue;
			} else {
				if(test.matches(proxy)) {
					if (axis == Constants.SELF_AXIS || axis == Constants.ANCESTOR_SELF_AXIS || 
							axis == Constants.DESCENDANT_SELF_AXIS) {				
						if(useSelfAsContext && inPredicate)
							proxy.addContextNode(contextId, proxy);
						result.add(proxy);
					}
				} 
				if (axis != Constants.SELF_AXIS) {
					domIter = proxy.getDocument().getBroker().getNodeIterator(proxy);
					StoredNode node = (StoredNode) domIter.next();
					node.setOwnerDocument(proxy.getDocument());
					node.setNodeId(proxy.getNodeId());
					addChildren(proxy, result, node, domIter, 0);
				}
			}
		}
		return result;
	}
	
	/** recursively adds children nodes */
	private final void addChildren(NodeProxy contextNode, NodeSet result, StoredNode node, Iterator iter,
	        int recursions) {
		if (node.hasChildNodes()) {
			StoredNode child;
			NodeProxy p;
			for (int i = 0; i < node.getChildCount(); i++) {
				child = (StoredNode) iter.next();
				if(child == null)
					LOG.debug("CHILD == NULL; doc = " + 
							((DocumentImpl)node.getOwnerDocument()).getName());
				if(node.getOwnerDocument() == null)
					LOG.debug("DOC == NULL");
				child.setOwnerDocument(node.getOwnerDocument());
				p = new NodeProxy((DocumentImpl)child.getOwnerDocument(), child.getNodeId(), 
                        child.getNodeType(), child.getInternalAddress());
				p.setMatches(contextNode.getMatches());
				if (test.matches(child)) {
					if (((axis == Constants.CHILD_AXIS
						|| axis == Constants.ATTRIBUTE_AXIS)
						&& recursions == 0) ||
						(axis == Constants.DESCENDANT_AXIS
						|| axis == Constants.DESCENDANT_SELF_AXIS
						|| axis == Constants.DESCENDANT_ATTRIBUTE_AXIS)) {
						p.deepCopyContext(contextNode);
						if (useSelfAsContext && inPredicate) {
							p.addContextNode(contextId, p);
						} else if (inPredicate)
							p.addContextNode(contextId, contextNode);
                        result.add(p);
					}
				}
				addChildren(contextNode, result, child, iter, recursions + 1);
			}
		}
	}

	public final void realize() {
		if (realSet != null && realSetIsComplete)
			return;
		realSet = getNodes();
		realSetIsComplete = true;
	}

	public void setSelfIsContext() {
		useSelfAsContext = true;
	}

	public void setContextId(int contextId) {
		this.contextId = contextId;
	}
	
	/* (non-Javadoc)
	 * @see org.exist.dom.NodeSet#hasIndex()
	 */
	public boolean hasIndex() {
		// Always return false: there's no index
		return false;
	}

	/* the following methods are normally never called in this context,
	 * we just provide them because they are declared abstract
	 * in the super class
	 */

	public void add(DocumentImpl doc, long nodeId) {
	}

	public void add(Node node) {
	}

	public void add(NodeProxy proxy) {
	}

	public void addAll(NodeList other) {
	}

	public void addAll(NodeSet other) {
	}

	public void set(int position, DocumentImpl doc, long nodeId) {
	}

	public void remove(NodeProxy node) {
	}

	public int getLength() {
		realize();
		return realSet.getLength();
	}

	public Node item(int pos) {
		realize();
		return realSet.item(pos);
	}

	public NodeProxy get(int pos) {
		realize();
		return realSet.get(pos);
	}

	public Item itemAt(int pos) {
		realize();
		return realSet.itemAt(pos);
	}

    public NodeProxy get(DocumentImpl doc, NodeId nodeId) {
        realize();
        return realSet.get(doc, nodeId);
    }

    public NodeProxy get(NodeProxy proxy) {
        realize();
        return realSet.get(proxy);
    }

	public NodeSetIterator iterator() {
		realize();
		return realSet.iterator();
	}

	/* (non-Javadoc)
	 * @see org.exist.dom.NodeSet#iterate()
	 */
	public SequenceIterator iterate() {
		realize();
		return realSet.iterate();
	}

	/* (non-Javadoc)
	 * @see org.exist.dom.AbstractNodeSet#unorderedIterator()
	 */
	public SequenceIterator unorderedIterator() {
		realize();
		return realSet.unorderedIterator();
	}
	
	public NodeSet intersection(NodeSet other) {
		realize();
		return realSet.intersection(other);
	}

	public NodeSet union(NodeSet other) {
		realize();
		return realSet.union(other);
	}
	
	public void clearContext() {
		// ignored for a virtual set
	}
    
    public String toString() {
        StringBuffer result = new StringBuffer();
        result.append("Virtual#").append(super.toString());
        return result.toString();
    }      
}
