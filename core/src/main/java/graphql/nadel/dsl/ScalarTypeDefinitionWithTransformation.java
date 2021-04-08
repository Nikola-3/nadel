package graphql.nadel.dsl;

import graphql.Internal;
import graphql.language.Comment;
import graphql.language.Description;
import graphql.language.Directive;
import graphql.language.IgnoredChars;
import graphql.language.NodeBuilder;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static graphql.Assert.assertNotNull;

@Internal
public class ScalarTypeDefinitionWithTransformation extends ScalarTypeDefinition {

    private final TypeMappingDefinition typeMappingDefinition;

    protected ScalarTypeDefinitionWithTransformation(TypeMappingDefinition typeMappingDefinition, String name, List<Directive> directives, Description description, SourceLocation sourceLocation, List<Comment> comments, IgnoredChars ignoredChars, Map<String, String> additionalData) {
        super(name, directives, description, sourceLocation, comments, ignoredChars, additionalData);
        this.typeMappingDefinition = typeMappingDefinition;
    }

    public TypeMappingDefinition getTypeMappingDefinition() {
        return typeMappingDefinition;
    }

    public static Builder newScalarTypeDefinitionWithTransformation(ScalarTypeDefinition existing) {
        return new Builder(existing);
    }

    public static final class Builder implements NodeBuilder {
        private SourceLocation sourceLocation;
        private List<Comment> comments = new ArrayList<>();
        private String name;
        private Description description;
        private List<Directive> directives = new ArrayList<>();
        private IgnoredChars ignoredChars = IgnoredChars.EMPTY;
        private TypeMappingDefinition typeMappingDefinition;
        private Map<String, String> additionalData = new LinkedHashMap<>();

        private Builder() {
        }

        private Builder(ScalarTypeDefinition existing) {
            this.sourceLocation = existing.getSourceLocation();
            this.comments = existing.getComments();
            this.name = existing.getName();
            this.description = existing.getDescription();
            this.directives = existing.getDirectives();
            this.ignoredChars = existing.getIgnoredChars();
        }


        public Builder typeMappingDefinition(TypeMappingDefinition typeMappingDefinition) {
            this.typeMappingDefinition = typeMappingDefinition;
            return this;
        }

        public Builder sourceLocation(SourceLocation sourceLocation) {
            this.sourceLocation = sourceLocation;
            return this;
        }

        public Builder comments(List<Comment> comments) {
            this.comments = comments;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(Description description) {
            this.description = description;
            return this;
        }

        public Builder directives(List<Directive> directives) {
            this.directives = directives;
            return this;
        }

        public Builder directive(Directive directive) {
            this.directives.add(directive);
            return this;
        }

        public Builder ignoredChars(IgnoredChars ignoredChars) {
            this.ignoredChars = ignoredChars;
            return this;
        }


        public Builder additionalData(Map<String, String> additionalData) {
            this.additionalData = assertNotNull(additionalData);
            return this;
        }

        public Builder additionalData(String key, String value) {
            this.additionalData.put(key, value);
            return this;
        }

        public ScalarTypeDefinition build() {
            return new ScalarTypeDefinitionWithTransformation(typeMappingDefinition,
                    name,
                    directives,
                    description,
                    sourceLocation,
                    comments,
                    ignoredChars,
                    additionalData
            );
        }
    }
}
