package graphql.nadel.e2e.nextgen

import graphql.GraphQLError
import graphql.language.AstSorter
import graphql.nadel.Nadel
import graphql.nadel.NadelExecutionInput
import graphql.nadel.NextgenEngine
import graphql.nadel.ServiceExecution
import graphql.nadel.ServiceExecutionFactory
import graphql.nadel.ServiceExecutionParameters
import graphql.nadel.ServiceExecutionResult
import graphql.nadel.engine.testutils.TestUtil
import graphql.parser.Parser
import spock.lang.Specification

import static graphql.language.AstPrinter.printAstCompact
import static graphql.nadel.NadelExecutionInput.newNadelExecutionInput
import static graphql.nadel.engine.testutils.TestUtil.typeDefinitions
import static java.util.concurrent.CompletableFuture.completedFuture

class NadelE2ETest extends Specification {
    def "simple deep rename"() {
        def nsdl = [IssueService: """
         service IssueService {
            type Query {
                issue: Issue
            } 
            type Issue {
                name: String => renamed from detail.detailName
            }
         }
        """]
        def underlyingSchema = """
            type Query {
                issue: Issue 
            } 
            type Issue {
                detail: IssueDetails
            }
            type IssueDetails {
                detailName: String
            }
        """
        def query = """
        { issue { name } } 
        """
        def expectedQuery = """query {
  ... on Query {
    issue {
      ... on Issue {
        my_uuid__detail: detail {
          ... on IssueDetails {
            detailName
          }
        }
      }
      ... on Issue {
        my_uuid__typename: __typename
      }
    }
  }
}"""
        def overallResponse = [issue: [name: "My Issue"]]
        def serviceResponse = [issue: [my_uuid__typename: "Issue", my_uuid__detail: [detailName: "My Issue"]]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                nsdl,
                'IssueService',
                underlyingSchema,
                query,
                expectedQuery,
                serviceResponse,
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "deep rename with interfaces"() {
        def serviceName = "PetService"
        def nsdl = [(serviceName): """
         service PetService {
            type Query {
                pets: [Pet]
            } 
            interface Pet {
                name: String 
            }
            type Dog implements Pet {
                name: String => renamed from detail.petName
            }
            type Cat implements Pet {
                name: String => renamed from detail.petName
            }
         }
        """]
        def underlyingSchema = """
            type Query {
                pets: [Pet]
            } 
            interface Pet {
                detail: PetDetails
            }
            type Dog implements Pet {
                detail: PetDetails
            }
            type Cat implements Pet {
                detail: PetDetails
            }
            type PetDetails {
                petName: String 
            }
        """
        def query = """
        { pets { name } } 
        """
        def expectedQuery = """query {
  ... on Query {
    pets {
      ... on Dog {
        my_uuid__detail: detail {
          ... on PetDetails {
            petName
          }
        }
      }
      ... on Cat {
        my_uuid__detail: detail {
          ... on PetDetails {
            petName
          }
        }
      }
      ... on Dog {
        my_uuid__typename: __typename
      }
      ... on Cat {
        my_uuid__typename: __typename
      }
    }
  }
}"""
        def serviceResponse = [pets: [
                [my_uuid__typename: "Cat", my_uuid__detail: [petName: "Tiger"]],
                [my_uuid__typename: "Dog", my_uuid__detail: [petName: "Luna"]],
        ]]

        def overallResponse = [pets: [[name: "Tiger"], [name: "Luna"]]]
        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                nsdl,
                serviceName,
                underlyingSchema,
                query,
                expectedQuery,
                serviceResponse,
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "different deep renames on same normalized field"() {
        def serviceName = "PetService"
        def nsdl = [(serviceName): """
         service PetService {
            type Query {
                pets: [Pet]
            } 
            interface Pet {
                name: String 
            }
            type Dog implements Pet {
                name: String => renamed from collar.petName
            }
            type Cat implements Pet {
                name: String => renamed from microchip.petName
            }
         }
        """]
        def underlyingSchema = """
            type Query {
                pets: [Pet]
            } 
            interface Pet {
                id: ID
            }
            type Dog implements Pet {
                id: ID
                collar: Collar
            }
            type Cat implements Pet {
                id: ID
                microchip: Microchip
            }
            type Microchip {
                petName: String
            }
            type Collar {
                petName: String
            }
        """
        def query = """
        { pets { name } } 
        """
        def expectedQuery = """query {
  ... on Query {
    pets {
      ... on Dog {
        my_uuid__collar: collar {
          ... on Collar {
            petName
          }
        }
      }
      ... on Cat {
        my_uuid__microchip: microchip {
          ... on Microchip {
            petName
          }
        }
      }
      ... on Dog {
        my_uuid__typename: __typename
      }
      ... on Cat {
        my_uuid__typename: __typename
      }
    }
  }
}"""
        def serviceResponse = [pets: [
                [my_uuid__typename: "Cat", my_uuid__microchip: [petName: "Tiger"]],
                [my_uuid__typename: "Dog", my_uuid__collar: [petName: "Luna"]],
        ]]

        def overallResponse = [pets: [[name: "Tiger"], [name: "Luna"]]]
        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                nsdl,
                serviceName,
                underlyingSchema,
                query,
                expectedQuery,
                serviceResponse,
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "simple type rename"() {
        def nsdl = [IssueService: """
         service IssueService {
            type Query {
                issue: Issue
            } 
            type Issue => renamed from UnderlyingIssue {
                name: String 
            }
         }
        """]
        def underlyingSchema = """
            type Query {
                issue: UnderlyingIssue 
            } 
            type UnderlyingIssue {
                name:String
            }
        """
        def query = """
        { issue { __typename name } } 
        """
        def expectedQuery = '''query {... on Query {issue {... on Issue {__typename} ... on Issue {name}}}}'''
        def serviceResponse = [issue: [__typename: "UnderlyingIssue", name: "My Issue"]]

        def overallResponse = [issue: [__typename: "Issue", name: "My Issue"]]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                nsdl,
                'IssueService',
                underlyingSchema,
                query,
                expectedQuery,
                serviceResponse,
        )
        then:
        errors.size() == 0
        response == overallResponse
    }

    def "input object type rename with query variables"() {
        def nsdl = [IssueService: """
         service IssueService {
            type Query {
                issue(arg: Input): String
            } 
            input Input => renamed from UnderlyingInput{
                foo: String
            }
         }
        """]
        def underlyingSchema = """
            type Query {
                issue(arg: Input): String
            } 
            input Input{
                foo: String
            }
        """
        def query = ''' 
        query($var: Input)
        { issue(arg: $var)}
        '''
        def rawVariables = [var: [foo: "bar"]]
        def expectedQuery = '''query {... on Query {issue(arg: {foo: "bar"})}}'''
        def serviceResponse = [issue: "hello"]

        def overallResponse = [issue: "hello"]

        Map response
        List<GraphQLError> errors
        when:
        (response, errors) = test1Service(
                nsdl,
                'IssueService',
                underlyingSchema,
                query,
                expectedQuery,
                serviceResponse,
                rawVariables
        )
        then:
        errors.size() == 0
        response == overallResponse
    }


    Object[] test1Service(Map<String, String> overallSchema,
                          String serviceOneName,
                          String underlyingSchema,
                          String query,
                          String expectedQuery,
                          Map serviceResponse,
                          Map rawVariables = [:]
    ) {
        def response1ServiceResult = new ServiceExecutionResult(serviceResponse)

        boolean calledService1 = false
        def astSorter = new AstSorter()
        ServiceExecution serviceExecution = { ServiceExecutionParameters sep ->
            calledService1 = true
            assert printAstCompact(
                    astSorter.sort(sep.query)
            ).tap {
                println "Actual query:"
                println it
            } == printAstCompact(
                    astSorter.sort(
                            new Parser().parseDocument(expectedQuery),
                    )
            ).tap {
                println "Expecting query:"
                println it
            }
            return completedFuture(response1ServiceResult)
        }

        ServiceExecutionFactory serviceFactory = TestUtil.serviceFactory([
                (serviceOneName): new Tuple2(serviceExecution, typeDefinitions(underlyingSchema))]
        )
        Nadel nadel = NextgenEngine.newNadel()
                .dsl(overallSchema)
                .serviceExecutionFactory(serviceFactory)
                .build()
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .variables(rawVariables)
                .artificialFieldsUUID("uuid")
                .build()

        def response = nadel.execute(nadelExecutionInput)

        def executionResult = response.get()
        assert calledService1

        return [executionResult.getData(), executionResult.getErrors()]
    }
}
