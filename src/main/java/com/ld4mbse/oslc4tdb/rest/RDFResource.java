package com.ld4mbse.oslc4tdb.rest;

import com.ld4mbse.oslc4tdb.model.OSLCModel;
import com.ld4mbse.oslc4tdb.model.ResourceDescriptor;
import com.ld4mbse.oslc4tdb.util.Requests;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static javax.ws.rs.core.HttpHeaders.ACCEPT;
import static javax.ws.rs.core.MediaType.*;
import static javax.ws.rs.core.Response.Status.*;

/**
 * Common super class for RDF resource.
 * @author rherrera
 */
public class RDFResource {
    /**
     * Logger of this class.
     */
    private static final Logger LOG = LoggerFactory.getLogger(RDFResource.class);

    @Context
    protected HttpServletRequest request;

    @Context
    protected HttpServletResponse response;

    /**
     * Determines whether an HTTP Accept header value is compatible with
     * {@link Lang#RDFXML}.
     * @param accept the Accept HTTP header value to test.
     * @return {@code true} if {@code accept} value is compatible with
     * {@code RDFXML}; {@code false} otherwise.
     */
    protected boolean isAcceptHeaderCompatibleWithRDFXML(String accept) {
        switch(accept) {
            case TEXT_HTML:
            case APPLICATION_XHTML_XML:
            case APPLICATION_XML:
            case WILDCARD:
                return true;
            default:
                return false;
        }
    }
    /**
     * Gets the most acceptable {@link Lang RDF Language} by the client
     * according to the HTTP Accept header value(s).
     * @return the most acceptable RDF Language by the client according to the
     * HTTP Accept header value(s); {@code null} if the requested Lang is not
     * acceptable.
     */
    protected Lang getAcceptableLanguage() {
        Lang acceptable;
        boolean isRDFXMLAcceptable;
        Requests.HeaderValueQuality headerValue;
        Iterator<Requests.HeaderValueQuality> headerValues;
        Enumeration<String> acceptHeaders = request.getHeaders(ACCEPT);
        if (acceptHeaders == null || !acceptHeaders.hasMoreElements())
            acceptable = Lang.RDFXML;
        else {
            acceptable = null;
            isRDFXMLAcceptable = false;
            headerValues = Requests.getQualifiedHeaderValues(acceptHeaders).iterator();
            while(acceptable == null && headerValues.hasNext()) {
                headerValue = headerValues.next();
                acceptable = RDFLanguages.contentTypeToLang(headerValue.getHeaderValue());
                if (!isRDFXMLAcceptable) isRDFXMLAcceptable = isAcceptHeaderCompatibleWithRDFXML(headerValue.getHeaderValue());
            }
            if (acceptable == null && isRDFXMLAcceptable)
                acceptable = Lang.RDFXML;
        }
        return acceptable;
    }
    /**
     * Dispatches a discovery resource back to the client.
     * @param resourceModel the resource to serialize back.
     * @param isResource indicates whether model represents an individual
     * resource and therefore an extra validation is performed to guarantee
     * the outcome model includes the resource which URL corresponds to the
     * HTTP request.
     */
    protected Response dispatchResource(String name, Model resourceModel, String type, boolean isResource) {
        Lang lang;
        OutputStream output;
        String uri = request.getRequestURL().toString();
        Resource resource = ResourceFactory.createResource(uri);
        try {
            if (resourceModel.isEmpty()) {
                return Response.status(NOT_FOUND)
                        .type(TEXT_PLAIN)
                        .entity("The resources doesn't exists.")
                        .build();
            }
            if (isResource && !resourceModel.containsResource(resource)) {
                return Response.status(CONFLICT)
                        .type(TEXT_PLAIN)
                        .entity("Internal resource URL does not match with requested URL")
                        .build();
            }
            if ((lang = getAcceptableLanguage()) == null) {
                return Response.status(NOT_ACCEPTABLE)
                        .type(TEXT_PLAIN)
                        .entity("Content not acceptable")
                        .build();
            }
            response.setContentType(lang.getContentType().toHeaderString());
            response.setStatus(HttpServletResponse.SC_OK);
            output = response.getOutputStream();
            RDFDataMgr.write(output, resourceModel.getGraph(), lang);
            output.flush();
            return Response.ok().build();

        } catch(RuntimeException | IOException e) {
            LOG.error("Could not get resource at " + uri, e);
            return Response.status(INTERNAL_SERVER_ERROR)
                    .type(TEXT_PLAIN)
                    .entity("Runtime Exception: " + e)
                    .build();
        }
    }

    private void render(String name,
                        LinkedHashMap<String, LinkedHashMap<String, String>> map,
                        Model model,
                        String renderPage) throws ServletException, IOException {
        RequestDispatcher rd = request.getRequestDispatcher(renderPage);
        request.setAttribute("graph", model);
        request.setAttribute("container", name);
        request.setAttribute("map", map);
        rd.forward(request, response);
    }

    public void renderGraph(String name, Model model) throws ServletException, IOException {
        String renderPage = "/WEB-INF/pages/graph.jsp";
        LinkedHashMap<String, LinkedHashMap<String, String>> map = new LinkedHashMap<>();
        map.put("Resources", getElements(model, "subject", OSLCModel.PROPS.PATHS.TYPE, 8));
        render(name, map, model, renderPage);
    }

    public void renderResource(Model model, Model shacl) throws URISyntaxException, ServletException, IOException {
        String renderPage = "/WEB-INF/pages/resource.jsp";
        ResourceDescriptor descriptor = ResourceDescriptor.getInstante(model, shacl);
        RequestDispatcher rd = request.getRequestDispatcher(renderPage);
        request.setAttribute("resource", descriptor);
        rd.forward(request, response);
    }

    private LinkedHashMap<String, String> getElements(Model model, String targetProperty, Property target, Integer position) {

        StmtIterator iterator;
        String key;
        LinkedHashMap<String, String> elements = new LinkedHashMap<>();

        SimpleSelector selector = new SimpleSelector(null, null, (RDFNode) null) {
            public boolean selects(Statement st) {
                return st.getPredicate().equals(target);
            }
        };

        iterator = model.listStatements(selector);

        while (iterator.hasNext()) {
            Statement st = iterator.nextStatement();
            switch (targetProperty) {
                case "subject":
                    if (st.getSubject().toString().split("/").length > position) {
                        key = st.getSubject().toString().split("/")[position];
                        elements.put(key, st.getSubject().toString());
                    }
                    break;
                case "predicate":
                    key = st.getPredicate().toString().split("/")[position];
                    elements.put(key, st.getObject().toString());
                    break;
                case "object":
                    if (st.getObject().toString().split("/").length > position) {
                        key = st.getObject().toString().split("/")[position];
                        if (key.equals("stores")) {
                            key = "All Types";
                        }

                        if (!key.equals("rdfstores")) elements.put(key, st.getObject().toString());
                    }
                    break;
            }
        }

        return elements;

    }

}
