package graphql.nadel.dsl;

import graphql.Internal;
import graphql.language.AbstractNode;
import graphql.language.IgnoredChars;
import graphql.language.Node;
import graphql.language.NodeChildrenContainer;
import graphql.language.NodeVisitor;
import graphql.language.SourceLocation;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

@Internal
public class RemoteArgumentDefinition extends AbstractNode<RemoteArgumentDefinition> {

    private final String name;
    private final RemoteArgumentSource remoteArgumentSource;

    public RemoteArgumentDefinition(String name,
                                    RemoteArgumentSource remoteArgumentSource,
                                    SourceLocation sourceLocation,
                                    Map<String, String> additionalData) {
        super(sourceLocation, emptyList(), IgnoredChars.EMPTY, additionalData);
        this.name = name;
        this.remoteArgumentSource = remoteArgumentSource;
    }

    public String getName() {
        return name;
    }

    public RemoteArgumentSource getRemoteArgumentSource() {
        return remoteArgumentSource;
    }

    @Override
    public List<Node> getChildren() {
        return new ArrayList<>();
    }

    @Override
    public NodeChildrenContainer getNamedChildren() {
        return null;
    }

    @Override
    public RemoteArgumentDefinition withNewChildren(NodeChildrenContainer newChildren) {
        return null;
    }

    @Override
    public boolean isEqualTo(Node node) {
        return false;
    }

    @Override
    public RemoteArgumentDefinition deepCopy() {
        return null;
    }

    @Override
    public TraversalControl accept(TraverserContext<Node> context, NodeVisitor visitor) {
        return null;
    }
}
