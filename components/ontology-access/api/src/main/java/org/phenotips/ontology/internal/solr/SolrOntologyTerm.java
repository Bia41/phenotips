/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.phenotips.ontology.internal.solr;

import org.phenotips.ontology.OntologyService;
import org.phenotips.ontology.OntologyTerm;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.common.SolrDocument;

/**
 * Implementation for {@link OntologyTerm} based on an indexed Solr document.
 * 
 * @version $Id$
 * @since 1.0M8
 */
public class SolrOntologyTerm implements OntologyTerm
{
    private static final String TERM_CATEGORY = "term_category";

    /** The Solr document representing this term. */
    private SolrDocument doc;

    /**
     * The owner ontology.
     * 
     * @see #getOntology()
     */
    private OntologyService ontology;

    /**
     * The parents of this term, transformed from a set of IDs into a real set of terms.
     * 
     * @see #getParents()
     */
    private Set<OntologyTerm> parents;

    /**
     * The ancestors of this term, transformed from a set of IDs into a real set of terms.
     * 
     * @see #getAncestors()
     */
    private Set<OntologyTerm> ancestors;

    /**
     * A set containing the term itself and its ancestors, transformed from a set of IDs into a real set of terms.
     * 
     * @see #getAncestorsAndSelf()
     */
    private Set<OntologyTerm> ancestorsAndSelf;

    /**
     * Constructor that provides the backing {@link #doc Solr document} and the {@link #ontology owner ontology}.
     * 
     * @param doc the {@link #doc Solr document} representing this term
     * @param ontology the {@link #ontology owner ontology}
     */
    public SolrOntologyTerm(SolrDocument doc, OntologyService ontology)
    {
        this.doc = doc;
        this.ontology = ontology;
        if (doc != null) {
            this.removeSelfDuplicate();
            this.parents = new LazySolrTermSet(doc.getFieldValues("is_a"), ontology);
            this.ancestors = new LazySolrTermSet(doc.getFieldValues(TERM_CATEGORY), ontology);
            Collection<Object> termSet = new HashSet<Object>();
            termSet.add(this.getId());
            if (doc.getFieldValues(TERM_CATEGORY) != null) {
                termSet.addAll(doc.getFieldValues(TERM_CATEGORY));
            }
            this.ancestorsAndSelf = new LazySolrTermSet(termSet, this.ontology);
        }
    }

    /**
     * The field "term_category" in {@code this.doc} can contain the term itself. It appears that this only happens with
     * HPO. To avoid this problem, and to avoid writing a separate implementation for HPO specifically, this method
     * checks for existence of the term in the term_category and takes it out.
     */
    private void removeSelfDuplicate()
    {
        Object value = this.doc.getFieldValue(TERM_CATEGORY);
        if (!(value instanceof List)) {
            return;
        }
        List listValue = (List) value;
        listValue.remove(this.getId());
    }

    @Override
    public String getId()
    {
        return this.doc != null ? (String) this.doc.getFirstValue("id") : null;
    }

    @Override
    public String getName()
    {
        return this.doc != null ? (String) this.doc.getFirstValue("name") : null;
    }

    @Override
    public String getDescription()
    {
        return this.doc != null ? (String) this.doc.getFirstValue("def") : null;
    }

    @Override
    public Set<OntologyTerm> getParents()
    {
        return this.parents != null ? this.parents : Collections.<OntologyTerm> emptySet();
    }

    @Override
    public Set<OntologyTerm> getAncestors()
    {
        return this.ancestors != null ? this.ancestors : Collections.<OntologyTerm> emptySet();
    }

    @Override
    public Set<OntologyTerm> getAncestorsAndSelf()
    {
        return this.ancestorsAndSelf != null ? this.ancestorsAndSelf : Collections.<OntologyTerm>emptySet();
    }

    @Override
    public Object get(String name)
    {
        return this.doc != null ? this.doc.getFieldValue(name) : null;
    }

    @Override
    public OntologyService getOntology()
    {
        return this.ontology;
    }

    @Override
    public String toString()
    {
        return "[" + this.getId() + "] " + this.getName();
    }

    @Override
    public long getDistanceTo(final OntologyTerm other)
    {
        if (other == null) {
            return -1;
        }
        if (this.equals(other)) {
            return 0;
        }

        long distance = Integer.MAX_VALUE;

        Map<String, Integer> myLevelMap = new HashMap<String, Integer>();
        myLevelMap.put(getId(), 0);
        Map<String, Integer> otherLevelMap = new HashMap<String, Integer>();
        otherLevelMap.put(other.getId(), 0);

        Set<OntologyTerm> myCrtLevel = new HashSet<OntologyTerm>();
        myCrtLevel.add(this);
        Set<OntologyTerm> otherCrtLevel = new HashSet<OntologyTerm>();
        otherCrtLevel.add(other);

        for (int l = 1; l <= distance && !myCrtLevel.isEmpty() && !otherCrtLevel.isEmpty(); ++l) {
            distance = Math.min(distance, processAncestorsAtDistance(l, myCrtLevel, myLevelMap, otherLevelMap));
            distance = Math.min(distance, processAncestorsAtDistance(l, otherCrtLevel, otherLevelMap, myLevelMap));
        }
        return distance == Integer.MAX_VALUE ? -1 : distance;
    }

    private long processAncestorsAtDistance(int localDistance, Set<OntologyTerm> sourceUnprocessedAncestors,
        Map<String, Integer> sourceDistanceMap, Map<String, Integer> targetDistanceMap)
    {
        long minDistance = Integer.MAX_VALUE;
        Set<OntologyTerm> nextLevel = new HashSet<OntologyTerm>();
        for (OntologyTerm term : sourceUnprocessedAncestors) {
            for (OntologyTerm parent : term.getParents()) {
                if (sourceDistanceMap.containsKey(parent.getId())) {
                    continue;
                }
                if (targetDistanceMap.containsKey(parent.getId())) {
                    minDistance = Math.min(minDistance, targetDistanceMap.get(parent.getId()) + localDistance);
                }
                nextLevel.add(parent);
                sourceDistanceMap.put(parent.getId(), localDistance);
            }
        }
        sourceUnprocessedAncestors.clear();
        sourceUnprocessedAncestors.addAll(nextLevel);

        return minDistance;
    }

    @Override
    public int hashCode()
    {
        String id = getId();
        return (id != null ? id.hashCode() : 0);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || !(obj instanceof OntologyTerm)) {
            return false;
        }
        return StringUtils.equals(getId(), ((OntologyTerm) obj).getId());
    }
}
