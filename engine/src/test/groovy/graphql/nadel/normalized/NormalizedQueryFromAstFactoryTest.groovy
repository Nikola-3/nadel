package graphql.nadel.normalized

import graphql.GraphQL
import graphql.introspection.Introspection
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
import graphql.nadel.testutils.TestUtil
import graphql.schema.GraphQLSchema
import graphql.util.TraversalControl
import graphql.util.Traverser
import graphql.util.TraverserContext
import graphql.util.TraverserVisitorStub
import spock.lang.Specification

import static graphql.nadel.dsl.NodeId.getId

class NormalizedQueryFromAstFactoryTest extends Specification {


    def "test"() {
        String schema = """
type Query{ 
    animal: Animal
}
interface Animal {
    name: String
    friends: [Friend]
}

union Pet = Dog | Cat

type Friend {
    name: String
    isBirdOwner: Boolean
    isCatOwner: Boolean
    pets: [Pet] 
}

type Bird implements Animal {
   name: String 
   friends: [Friend]
}

type Cat implements Animal{
   name: String 
   friends: [Friend]
   breed: String 
}

type Dog implements Animal{
   name: String 
   breed: String
   friends: [Friend]
}
    
        """
        GraphQLSchema graphQLSchema = TestUtil.schema(schema)

        String query = """
        {
            animal{
                name
                otherName: name
               ... on Cat {
                    name
                    friends {
                        ... on Friend {
                            isCatOwner
                        }
                   } 
               }
               ... on Bird {
                    friends {
                        isBirdOwner
                    }
                    friends {
                        name
                    }
               }
               ... on Dog {
                  name   
               }
        }}
        
        """

        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def printedTree = printTree(tree)

        expect:
        printedTree == ['Query.animal: Animal (conditional: false)',
                        'Bird.name: String (conditional: true)',
                        'Cat.name: String (conditional: true)',
                        'Dog.name: String (conditional: true)',
                        'otherName: Bird.name: String (conditional: true)',
                        'otherName: Cat.name: String (conditional: true)',
                        'otherName: Dog.name: String (conditional: true)',
                        'Cat.friends: [Friend] (conditional: true)',
                        'Friend.isCatOwner: Boolean (conditional: false)',
                        'Bird.friends: [Friend] (conditional: true)',
                        'Friend.isBirdOwner: Boolean (conditional: false)',
                        'Friend.name: String (conditional: false)']

    }

    def "test2"() {
        String schema = """
        type Query{ 
            a: A
        }
        interface A {
           b: B  
        }
        type A1 implements A {
           b: B 
        }
        type A2 implements A{
            b: B
        }
        interface B {
            leaf: String
        }
        type B1 implements B {
            leaf: String
        } 
        type B2 implements B {
            leaf: String
        } 
    
        """
        GraphQLSchema graphQLSchema = TestUtil.schema(schema)

        String query = """
        {
            a {
            ... on A {
                myAlias: b { leaf }
            }
                ... on A1 {
                   b { 
                     ... on B1 {
                        leaf
                        }
                     ... on B2 {
                        leaf
                        }
                   }
                }
                ... on A1 {
                   b { 
                     ... on B1 {
                        leaf
                        }
                   }
                }
                ... on A2 {
                    b {
                       ... on B2 {
                            leaf
                       } 
                    }
                }
            }
        }
        
        """

        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def printedTree = printTree(tree)

        expect:
        printedTree == ['Query.a: A (conditional: false)',
                        'myAlias: A1.b: B (conditional: true)',
                        'B1.leaf: String (conditional: true)',
                        'B2.leaf: String (conditional: true)',
                        'myAlias: A2.b: B (conditional: true)',
                        'B1.leaf: String (conditional: true)',
                        'B2.leaf: String (conditional: true)',
                        'A1.b: B (conditional: true)',
                        'B1.leaf: String (conditional: true)',
                        'B2.leaf: String (conditional: true)',
                        'A2.b: B (conditional: true)',
                        'B2.leaf: String (conditional: true)']
    }

    def "test3"() {
        String schema = """
        type Query{ 
            a: [A]
            object: Object
        }
        type Object {
            someValue: String
        }
        interface A {
           b: B  
        }
        type A1 implements A {
           b: B 
        }
        type A2 implements A{
            b: B
        }
        interface B {
            leaf: String
        }
        type B1 implements B {
            leaf: String
        } 
        type B2 implements B {
            leaf: String
        } 
    
        """
        GraphQLSchema graphQLSchema = TestUtil.schema(schema)

        String query = """
        {
          object{someValue}
          a {
            ... on A1 {
              b {
                ... on B {
                  leaf
                }
                ... on B1 {
                  leaf
                }
                ... on B2 {
                  ... on B {
                    leaf
                  }
                  leaf
                  leaf
                  ... on B2 {
                    leaf
                  }
                }
              }
            }
          }
        }
        """

        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def printedTree = printTree(tree)

        expect:
        printedTree == ['Query.object: Object (conditional: false)',
                        'Object.someValue: String (conditional: false)',
                        'Query.a: [A] (conditional: false)',
                        'A1.b: B (conditional: true)',
                        'B1.leaf: String (conditional: true)',
                        'B2.leaf: String (conditional: true)']

    }

    def "test impossible type condition"() {

        String schema = """
        type Query{ 
            pets: [Pet]
        }
        interface Pet {
            name: String
        }
        type Cat implements Pet {
            name: String
        }
        type Dog implements Pet{
            name: String
        }
        union CatOrDog = Cat | Dog
        """
        GraphQLSchema graphQLSchema = TestUtil.schema(schema)

        String query = """
        {
            pets {
                ... on Dog {
                    ... on CatOrDog {
                    ... on Cat{
                            name
                            }
                    }
                }
            }
        }
        
        """
        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def printedTree = printTree(tree)

        expect:
        printedTree == ['Query.pets: [Pet] (conditional: false)']

    }

    def "query with unions and __typename"() {

        String schema = """
        type Query{ 
            pets: [CatOrDog]
        }
        type Cat {
            catName: String
        }
        type Dog {
            dogName: String
        }
        union CatOrDog = Cat | Dog
        """
        GraphQLSchema graphQLSchema = TestUtil.schema(schema)

        String query = """
        {
            pets {
                __typename
                ... on Cat {
                    catName 
                }  
                ... on Dog {
                    dogName
                }
            }
        }
        
        """
        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def printedTree = printTree(tree)

        expect:
        printedTree == ['Query.pets: [CatOrDog] (conditional: false)',
                        'Cat.__typename: String! (conditional: true)',
                        'Dog.__typename: String! (conditional: true)',
                        'Cat.catName: String (conditional: true)',
                        'Dog.dogName: String (conditional: true)']

    }

    def "query with interface"() {

        String schema = """
        type Query{ 
            pets: [Pet]
        }
        interface Pet {
            id: ID
        }
        type Cat implements Pet{
            id: ID
            catName: String
        }
        type Dog implements Pet{
            id: ID
            dogName: String
        }
        """
        GraphQLSchema graphQLSchema = TestUtil.schema(schema)

        String query = """
        {
            pets {
                id
                ... on Cat {
                    catName 
                }  
                ... on Dog {
                    dogName
                }
            }
        }
        
        """
        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def printedTree = printTree(tree)

        expect:
        printedTree == ['Query.pets: [Pet] (conditional: false)',
                        'Cat.id: ID (conditional: true)',
                        'Dog.id: ID (conditional: true)',
                        'Cat.catName: String (conditional: true)',
                        'Dog.dogName: String (conditional: true)']

    }

    def "test5"() {
        String schema = """
        type Query{ 
            a: [A]
        }
        interface A {
           b: String
        }
        type A1 implements A {
           b: String 
        }
        type A2 implements A{
            b: String
            otherField: A
        }
        type A3  implements A {
            b: String
        }
    
        """
        GraphQLSchema graphQLSchema = TestUtil.schema(schema)


        def query = """
        {
            a {
                b
                ... on A1 {
                   b 
                }
                ... on A2 {
                    b 
                    otherField {
                    ... on A2 {
                            b
                        }
                        ... on A3 {
                            b
                        }
                    }
                    
                }
            }
        }
        
        """

        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def printedTree = printTree(tree)

        expect:
        printedTree == ['Query.a: [A] (conditional: false)',
                        'A1.b: String (conditional: true)',
                        'A2.b: String (conditional: true)',
                        'A3.b: String (conditional: true)',
                        'A2.otherField: A (conditional: true)',
                        'A2.b: String (conditional: true)',
                        'A3.b: String (conditional: true)']

    }

    def "test6"() {
        String schema = """
        type Query {
            issues: [Issue]
        }

        type Issue {
            id: ID
            author: User
        }
        type User {
            name: String
            createdIssues: [Issue] 
        }
        """
        GraphQLSchema graphQLSchema = TestUtil.schema(schema)

        def query = """{ issues {
                    author {
                        name
                        ... on User {
                            createdIssues {
                                id
                            }
                        }
                    }
                }}
                """

        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def printedTree = printTree(tree)

        expect:
        printedTree == ['Query.issues: [Issue] (conditional: false)',
                        'Issue.author: User (conditional: false)',
                        'User.name: String (conditional: false)',
                        'User.createdIssues: [Issue] (conditional: false)',
                        'Issue.id: ID (conditional: false)']

    }

    def "test7"() {
        String schema = """
        type Query {
            issues: [Issue]
        }

        type Issue {
            authors: [User]
        }
        type User {
            name: String
            friends: [User]
        }
        """
        GraphQLSchema graphQLSchema = TestUtil.schema(schema)

        def query = """{ issues {
                    authors {
                       friends {
                            friends {
                                name
                            }
                       } 
                   }
                }}
                """

        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def printedTree = printTree(tree)

        expect:
        printedTree == ['Query.issues: [Issue] (conditional: false)',
                        'Issue.authors: [User] (conditional: false)',
                        'User.friends: [User] (conditional: false)',
                        'User.friends: [User] (conditional: false)',
                        'User.name: String (conditional: false)']

    }

    def "query with fragment definition"() {
        def graphQLSchema = TestUtil.schema("""
            type Query{
                foo: Foo
            }
            type Foo {
                subFoo: String  
                moreFoos: Foo
            }
        """)
        def query = """
            {foo { ...fooData moreFoos { ...fooData }}} fragment fooData on Foo { subFoo }
            """
        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def printedTree = printTree(tree)

        expect:
        printedTree == ['Query.foo: Foo (conditional: false)',
                        'Foo.subFoo: String (conditional: false)',
                        'Foo.moreFoos: Foo (conditional: false)',
                        'Foo.subFoo: String (conditional: false)']
    }

    def "query with interface in between"() {
        def graphQLSchema = TestUtil.schema("""
        type Query {
            pets: [Pet]
        }
        interface Pet {
            name: String
            friends: [Human]
        }
        type Human {
            name: String
        }
        type Cat implements Pet {
            name: String
            friends: [Human]
        }
        type Dog implements Pet {
            name: String
            friends: [Human]
        }
        """)
        def query = """
            { pets { friends {name} } }
            """
        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def printedTree = printTree(tree)

        expect:
        printedTree == ['Query.pets: [Pet] (conditional: false)',
                        'Cat.friends: [Human] (conditional: true)',
                        'Human.name: String (conditional: false)',
                        'Dog.friends: [Human] (conditional: true)',
                        'Human.name: String (conditional: false)']
    }


    List<String> printTree(NormalizedQueryFromAst queryExecutionTree) {
        def result = []
        Traverser<NormalizedQueryField> traverser = Traverser.depthFirst({ it.getChildren() });
        traverser.traverse(queryExecutionTree.getTopLevelFields(), new TraverserVisitorStub<NormalizedQueryField>() {
            @Override
            TraversalControl enter(TraverserContext<NormalizedQueryField> context) {
                NormalizedQueryField queryExecutionField = context.thisNode();
                result << queryExecutionField.print()
                return TraversalControl.CONTINUE;
            }
        });
        result
    }

    def "normalized fields map by field id is build"() {
        def graphQLSchema = TestUtil.schema("""
            type Query{
                foo: Foo
            }
            type Foo {
                subFoo: String  
                moreFoos: Foo
            }
        """)
        def query = """
            {foo { ...fooData moreFoos { ...fooData }}} fragment fooData on Foo { subFoo }
            """
        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)
        def subFooField = (document.getDefinitions()[1] as FragmentDefinition).getSelectionSet().getSelections()[0] as Field
        def subFooFieldId = getId(subFooField)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def normalizedFieldsByFieldId = tree.getNormalizedFieldsByFieldId()

        expect:
        normalizedFieldsByFieldId.size() == 3
        normalizedFieldsByFieldId.get(subFooFieldId).size() == 2
        normalizedFieldsByFieldId.get(subFooFieldId)[0].level == 2
        normalizedFieldsByFieldId.get(subFooFieldId)[1].level == 3
    }

    def "normalized fields map with interfaces "() {

        String schema = """
        type Query{ 
            pets: [Pet]
        }
        interface Pet {
            id: ID
        }
        type Cat implements Pet{
            id: ID
        }
        type Dog implements Pet{
            id: ID
        }
        """
        GraphQLSchema graphQLSchema = TestUtil.schema(schema)

        String query = """
        {
            pets {
                id
            }
        }
        
        """
        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)
        def petsField = (document.getDefinitions()[0] as OperationDefinition).getSelectionSet().getSelections()[0] as Field
        def idField = petsField.getSelectionSet().getSelections()[0] as Field
        def idFieldId = getId(idField)

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def normalizedFieldsByFieldId = tree.getNormalizedFieldsByFieldId()

        expect:
        normalizedFieldsByFieldId.size() == 2
        normalizedFieldsByFieldId.get(idFieldId).size() == 2
        normalizedFieldsByFieldId.get(idFieldId)[0].objectType.name == "Cat"
        normalizedFieldsByFieldId.get(idFieldId)[1].objectType.name == "Dog"


    }

    def "query with introspection fields"() {
        String schema = """
        type Query{ 
            foo: String
        }
        """
        GraphQLSchema graphQLSchema = TestUtil.schema(schema)

        String query = """
        {
            __typename
            alias: __typename
            __schema {  queryType { name } }
            __type(name: "Query") {name}
            ...F
        }
        fragment F on Query {
            __typename
            alias: __typename
            __schema {  queryType { name } }
            __type(name: "Query") {name}
        }
        
        
        """
        assertValidQuery(graphQLSchema, query)

        Document document = TestUtil.parseQuery(query)
        def selections = (document.getDefinitions()[0] as OperationDefinition).getSelectionSet().getSelections()
        def typeNameField = selections[0]
        def aliasedTypeName = selections[1]
        def schemaField = selections[2]
        def typeField = selections[3]

        NormalizedQueryFactory dependencyGraph = new NormalizedQueryFactory();
        def tree = dependencyGraph.createNormalizedQuery(graphQLSchema, document, null, [:])
        def normalizedFieldsByFieldId = tree.getNormalizedFieldsByFieldId()

        expect:
        normalizedFieldsByFieldId.size() == 14
        normalizedFieldsByFieldId.get(getId(typeNameField))[0].objectType.name == "Query"
        normalizedFieldsByFieldId.get(getId(typeNameField))[0].fieldDefinition == Introspection.TypeNameMetaFieldDef
        normalizedFieldsByFieldId.get(getId(aliasedTypeName))[0].alias == "alias"
        normalizedFieldsByFieldId.get(getId(aliasedTypeName))[0].fieldDefinition == Introspection.TypeNameMetaFieldDef

        normalizedFieldsByFieldId.get(getId(schemaField))[0].objectType.name == "Query"
        normalizedFieldsByFieldId.get(getId(schemaField))[0].fieldDefinition == Introspection.SchemaMetaFieldDef

        normalizedFieldsByFieldId.get(getId(typeField))[0].objectType.name == "Query"
        normalizedFieldsByFieldId.get(getId(typeField))[0].fieldDefinition == Introspection.TypeMetaFieldDef

    }


    private void assertValidQuery(GraphQLSchema graphQLSchema, String query) {
        GraphQL graphQL = GraphQL.newGraphQL(graphQLSchema).build();
        assert graphQL.execute(query).errors.size() == 0
    }
}
