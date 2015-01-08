package cz.cvut.fit.gephi.multimode;

import java.util.*;
import org.gephi.data.attributes.api.AttributeColumn;
import org.gephi.data.attributes.api.AttributeController;
import org.gephi.data.attributes.api.AttributeModel;
import org.gephi.data.attributes.api.AttributeType;
import org.gephi.graph.api.*;
import org.gephi.utils.longtask.spi.LongTask;
import org.gephi.utils.progress.Progress;
import org.gephi.utils.progress.ProgressTicket;
import org.openide.util.Lookup;

/**
 *
 * @author Jaroslav Kuchar
 */
public class LongTaskTransformation implements LongTask, Runnable {
    
    private ProgressTicket progressTicket;
    private boolean cancelled = false;
    private AttributeColumn attributeColumn = null;
    private String inDimension;
    private String commonDimension;
    private String outDimension;
    private boolean removeEdges = true;
    private boolean removeNodes = true;
    
    public LongTaskTransformation(AttributeColumn attributeColumn, String inDimension, String commonDimension, String outDimension, boolean removeEdges, boolean removeNodes) {
        this.attributeColumn = attributeColumn;
        this.inDimension = inDimension;
        this.commonDimension = commonDimension;
        this.outDimension = outDimension;
        this.removeEdges = removeEdges;
        this.removeNodes = removeNodes;
    }
    
    @Override
    public void run() {
        // number of tickets
        Progress.start(progressTicket, 5);

        // graph
        GraphController graphController = Lookup.getDefault().lookup(GraphController.class);
        GraphModel graphModel = graphController.getModel();
        Graph graph = graphModel.getGraphVisible();
        //Graph graph = graphModel.getUndirectedGraphVisible();
        Node[] nodes = graph.getNodes().toArray();

        // matrix axis
        List<Node> firstHorizontal = new ArrayList<Node>();
        List<Node> firstVertical = new ArrayList<Node>();
        List<Node> secondHorizontal = new ArrayList<Node>();
        List<Node> secondVertical = new ArrayList<Node>();
        for (Node n : nodes) {
            String nodeValue = null;
            Object val = Utils.getValue(n, attributeColumn);
            if (val != null) {
                nodeValue = val.toString();
            } else {
                nodeValue = "null";
            }
            // matrix axis
            if (nodeValue.equals(inDimension)) {
                firstVertical.add(n);
            }
            if (nodeValue.equals(commonDimension)) {
                firstHorizontal.add(n);
                secondVertical.add(n);
            }
            if (nodeValue.equals(outDimension)) {
                secondHorizontal.add(n);
            }
        }
        
        if (cancelled) {
            return;
        }
        Progress.progress(progressTicket);

        // first matrix
        Matrix firstMatrix = new Matrix(firstVertical.size(), firstHorizontal.size());
        for (int i = 0; i < firstVertical.size(); i++) {
            Set<Node> intersection = new HashSet<Node>(Arrays.asList(graph.getNeighbors(firstVertical.get(i)).toArray()));
            if (intersection != null && intersection.size() > 0) {
                try {
                    intersection.retainAll(firstHorizontal);
                    for (Node neighbour : intersection) {
                     int j=firstHorizontal.indexOf(neighbour);
                     if (j > -1){
                        float w=graph.getEdge(firstVertical.get(i), firstHorizontal.get(j)).getWeight();
                        firstMatrix.set(i, j, w);
                    }
                }} catch (UnsupportedOperationException ex) {
                    System.out.println("exception");
                    // TODO - exception handler
                }
            }
        }
        // second matrix
        Matrix secondMatrix = new Matrix(secondVertical.size(), secondHorizontal.size());
        for (int i = 0; i < secondVertical.size(); i++) {
            
            Set<Node> intersection = new HashSet<Node>(Arrays.asList(graph.getNeighbors(secondVertical.get(i)).toArray()));
            if (intersection != null && intersection.size() > 0) {
                try {
                    intersection.retainAll(secondHorizontal);
                         for (Node neighbour : intersection) {
                            int j=secondHorizontal.indexOf(neighbour);
                            if (j>-1){                    
                                float w=graph.getEdge(secondVertical.get(i), secondHorizontal.get(j)).getWeight();
                                secondMatrix.set(i, j, w);
                            }
                         }
                } catch (UnsupportedOperationException ex) {
                    System.out.println("exception");
                    // TODO - exception handler
                }
            }
        }
        if (cancelled) {
            return;
        }
        Progress.progress(progressTicket, "Multiplication");
        
        Matrix result = firstMatrix.timesParallelIndexed(secondMatrix);
        if (cancelled) {
            return;
        }
        Progress.progress(progressTicket, "Removing nodes/edges");
        
        
        if (removeNodes) {
            for (Node n : firstHorizontal) {
                graph.removeNode(n);
            }
        } else {
            if (removeEdges) {
                for (int i = 0; i < firstMatrix.getM(); i++) {
                    for (int j = 0; j < firstMatrix.getN(); j++) {
                        if (graph.contains(firstVertical.get(i)) && graph.contains(firstHorizontal.get(j)) && graph.getEdge(firstVertical.get(i), firstHorizontal.get(j)) != null && firstMatrix.get(i, j) > 0) {
                            graph.removeEdge(graph.getEdge(firstVertical.get(i), firstHorizontal.get(j)));
                        }
                    }
                }
                
                for (int i = 0; i < secondMatrix.getM(); i++) {
                    for (int j = 0; j < secondMatrix.getN(); j++) {
                        if (graph.contains(secondVertical.get(i)) && graph.contains(secondHorizontal.get(j)) && graph.getEdge(secondVertical.get(i), secondHorizontal.get(j)) != null && secondMatrix.get(i, j) > 0) {
                            graph.removeEdge(graph.getEdge(secondVertical.get(i), secondHorizontal.get(j)));
                        }
                    }
                }
            }
        }
        
        if (cancelled) {
            return;
        }
        Progress.progress(progressTicket, "Creating new edges");
        AttributeController ac = Lookup.getDefault().lookup(AttributeController.class);
        AttributeModel model = ac.getModel();        
        AttributeColumn edgeTypeCol = model.getEdgeTable().getColumn("MMNT-EdgeType");        
        if (edgeTypeCol == null) {
            edgeTypeCol = model.getEdgeTable().addColumn("MMNT-EdgeType", AttributeType.STRING);
        }
        
        
        Edge ee = null;
        for (int i = 0; i < result.getM(); i++) {
            for (int j = 0; j < result.getN(); j++) {
                if (graph.contains(firstVertical.get(i)) && graph.contains(secondHorizontal.get(j)) && graph.getEdge(firstVertical.get(i), secondHorizontal.get(j)) == null && result.get(i, j) > 0) {
                    ee = graphModel.factory().newEdge(firstVertical.get(i), secondHorizontal.get(j), (float) result.get(i, j), graphModel.isDirected());
                    if (!ee.isSelfLoop()) {
                        ee.getEdgeData().getAttributes().setValue(edgeTypeCol.getIndex(), inDimension + "<--->" + outDimension);
                        //ee.getEdgeData().setLabel(inDimension + "-" + outDimension);
                        graph.addEdge(ee);
                    }
                }
            }
        }
        Progress.finish(progressTicket);
    }
    
    @Override
    public boolean cancel() {
        cancelled = true;
        return true;
    }
    
    @Override
    public void setProgressTicket(ProgressTicket pt) {
        this.progressTicket = pt;
    }
}
