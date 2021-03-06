package org.neo4j.cypher.docgen

import org.neo4j.cypher.docgen.tooling._
import org.neo4j.graphdb.Label
import org.neo4j.kernel.api.KernelTransaction.Type
import org.neo4j.kernel.api.security.AnonymousContext

import scala.collection.JavaConverters._

class SecurityPrivilegesTest extends DocumentingTest with QueryStatisticsTestSupport {
  override def outputPath = "target/docs/dev/ql/administration/security/"

  override def doc: Document = new DocBuilder {
    doc("Graph and Sub-graph Access Control", "administration-security-subgraph")
    database("system")
    initQueries(
      "CREATE USER jake SET PASSWORD 'abc123' CHANGE NOT REQUIRED SET STATUS ACTIVE",
      "CREATE ROLE regularUsers",
      "CREATE ROLE noAccessUsers",
      "GRANT ROLE regularUsers TO jake",
      "GRANT ACCESS ON DATABASE neo4j TO regularUsers",
      "DENY ACCESS ON DATABASE neo4j TO noAccessUsers"
    )
    synopsis("This section explains how to use Cypher to manage privileges for Neo4j role-based access control and fine-grained security.")
    p(
      """
        |Privileges control the access rights to graph elements using a combined whitelist/blacklist mechanism.
        |It is possible to grant access, or deny access, or a combination of the two.
        |The user will be able to access the resource if they have a grant (whitelist) and do not have a deny (blacklist) relevant to that resource.
        |All other combinations of `GRANT` and `DENY` will result in the matching subgraph being invisible.
        |It will appear to the user as if they have a smaller database (smaller graph).
        |""".stripMargin)
    note {
      p(
        """If a user was not also provided with the database `ACCESS` privilege then access to the entire database will be denied.
          |Information about the database access privilege can be found in <<administration-security-administration-database-access, The ACCESS privilege>>.
          |""".stripMargin)
    }
    section("The GRANT, DENY and REVOKE commands", "administration-security-subgraph-introduction") {
      p("include::grant-deny-syntax.asciidoc[]")
      p("image::grant-privileges-graph.png[title=\"GRANT and DENY Syntax\"]")
    }
    section("Listing privileges", "administration-security-subgraph-show") {
      p("Available privileges for all roles can be seen using `SHOW PRIVILEGES`.")
      query("SHOW PRIVILEGES", assertPrivilegeShown(Seq(
        Map("grant" -> "GRANTED", "action" -> "access", "role" -> "regularUsers"),
        Map("grant" -> "DENIED", "action" -> "access", "role" -> "noAccessUsers")
      ))) {
        p("Lists all privileges for all roles")
        resultTable()
      }

      p("Available privileges for a particular role can be seen using `SHOW ROLE name PRIVILEGES`.")
      query("SHOW ROLE regularUsers PRIVILEGES", assertPrivilegeShown(Seq(
        Map("grant" -> "GRANTED", "action" -> "access", "role" -> "regularUsers")
      ))) {
        p("Lists all privileges for role 'regularUsers'")
        resultTable()
      }

      p("Available privileges for a particular user can be seen using `SHOW USER name PRIVILEGES`.")
      query("SHOW USER jake PRIVILEGES", assertPrivilegeShown(Seq(
        Map("grant" -> "GRANTED", "action" -> "access", "role" -> "regularUsers", "user" -> "jake")
      ))) {
        p("Lists all privileges for user 'jake'")
        resultTable()
      }
    }

    section("The TRAVERSE privilege", "administration-security-subgraph-traverse") {
      p("Users can be granted the right to find nodes and relationships using the `GRANT TRAVERSE` privilege.")
      p("include::grant-traverse-syntax.asciidoc[]")
      p("For example, we can allow the user `jake`, who has role 'regularUsers' to find all nodes with the label `Post`.")
      query("GRANT TRAVERSE ON GRAPH neo4j NODES Post TO regularUsers", ResultAssertions((r) => {
        assertStats(r, systemUpdates = 1)
      })) {
        statsOnlyResultTable()
      }

      p("The `TRAVERSE` privilege can also be denied.")
      p("include::deny-traverse-syntax.asciidoc[]")
      p("For example, we can disallow the user `jake`, who has role 'regularUsers' to find all nodes with the label `Payments`.")
      query("DENY TRAVERSE ON GRAPH neo4j NODES Payments TO regularUsers", ResultAssertions((r) => {
        assertStats(r, systemUpdates = 1)
      })) {
        statsOnlyResultTable()
      }
    }

    section("The READ privilege", "administration-security-subgraph-read") {
      p(
        """Users can be granted the right to do property reads on nodes and relationships using the `GRANT READ` privilege.
          |It is very important to note that users can only read properties on entities that they is allowed to find in the first place.""".stripMargin)
      p("include::grant-read-syntax.asciidoc[]")

      p(
        """For example, we can allow the user `jake`, who has role 'regularUsers' to read all properties on nodes with the label `Post`.
          |The `*` implies that the ability to read all properties also extends to properties that might be added in the future.""".stripMargin)
      query("GRANT READ { * } ON GRAPH neo4j NODES Post TO regularUsers", ResultAssertions((r) => {
        assertStats(r, systemUpdates = 1)
      })) {
        statsOnlyResultTable()
      }

      p("The `READ` privilege can also be denied.")
      p("include::deny-read-syntax.asciidoc[]")

      p("Although we just granted the user 'jake' the right to read all properties, we may want to hide the `secret` property. The following example shows how to do that.")
      query("DENY READ { secret } ON GRAPH neo4j NODES Post TO regularUsers", ResultAssertions((r) => {
        assertStats(r, systemUpdates = 1)
      })) {
        statsOnlyResultTable()
      }
    }
    section("The MATCH privilege", "administration-security-subgraph-match") {
      p("As a shorthand for `TRAVERSE` and `READ`, users can be granted the right to find and do property reads on nodes and relationships using the `GRANT MATCH` privilege. ")
      p("include::grant-match-syntax.asciidoc[]")

      p(
        """For example if you want to grant the ability to read the properties `language` and `length` for nodes with the label `Message`,
          |as well as the ability to find these nodes, to a role `regularUsers` you can use the following `GRANT MATCH` query.""".stripMargin)

      query("GRANT MATCH { language, length } ON GRAPH neo4j NODES Message TO regularUsers", ResultAssertions((r) => {
        assertStats(r, systemUpdates = 3)
      })) {
        statsOnlyResultTable()
      }

      p("""Like all other privileges, the `MATCH` privilege can also be denied.""".stripMargin)
      p("include::deny-match-syntax.asciidoc[]")

      p(
        """Please note that the effect of denying a `MATCH` privilege depends on whether concrete property keys are specified or a `*`.
          |If you specify concrete property keys then `DENY MATCH` will only deny reading those properties. Finding the elements to traverse would still be allowed.
          |If you specify `*` instead then both traversal of the element and all property reads will be disallowed.
          |The following queries will show examples for this.""".stripMargin)

      p(
        """Denying to read the property ´content´ on nodes with the label `Message` for the role `regularUsers` would look like the following query.
          |Although not being able to read this specific property, nodes with that label can still be traversed (and, depending on other grants, other properties on it could still be read).""".stripMargin)

      query("DENY MATCH { content } ON GRAPH neo4j NODES Message TO regularUsers", ResultAssertions((r) => {
        assertStats(r, systemUpdates = 1)
      })) {
        statsOnlyResultTable()
      }

      p("The following query exemplifies how it would look like if you want to deny both reading all properties and traversing nodes labeled with `Account`.")

      query("DENY MATCH { * } ON GRAPH neo4j NODES Account TO regularUsers", ResultAssertions((r) => {
        assertStats(r, systemUpdates = 2)
      })) {
        statsOnlyResultTable()
      }

      note {
        p("Please note that `REVOKE MATCH` is not allowed, instead revoke the individual `READ` and `TRAVERSE` privileges.")
      }
    }

    section("The WRITE privilege", "administration-security-subgraph-write") {
      p(
        """The `WRITE` privilege can be used to allow the ability to write on a graph. At the moment, granting the `WRITE` privilege implies that you can do any write operation on any part of the graph. """.stripMargin)
      p("include::grant-write-syntax.asciidoc[]")

      p(
        """For example, granting the ability to write on the graph `neo4j` to the role `regularUsers` is done like the following query.""".stripMargin)
      query("GRANT WRITE { * } ON GRAPH neo4j ELEMENTS * TO regularUsers", ResultAssertions((r) => {
        assertStats(r, systemUpdates = 2)
      })) {
        statsOnlyResultTable()
      }

      p("The `WRITE` privilege can also be denied.")
      p("include::deny-write-syntax.asciidoc[]")

      p("For example, denying the ability to write on the graph `neo4j` to the role `regularUsers` is done like the following query.")
      query("DENY WRITE { * } ON GRAPH neo4j ELEMENTS * TO regularUsers", ResultAssertions((r) => {
        assertStats(r, systemUpdates = 2)
      })) {
        statsOnlyResultTable()
      }
    }

    section("The REVOKE command", "administration-security-subgraph-revoke") {
      p("Privileges that were granted or denied earlier can be revoked using the `REVOKE` command. ")
      p("include::revoke-syntax.asciidoc[]")

      note {
        p("Please note that `REVOKE MATCH` is not allowed, instead revoke the individual `READ` and `TRAVERSE` privileges.")
      }

      p("An example usage of the `REVOKE` command is given here:")
      query("REVOKE GRANT TRAVERSE ON GRAPH neo4j NODES Post TO regularUsers", ResultAssertions((r) => {
        assertStats(r, systemUpdates = 1)
      })) {}
      p(
        """While it can be explicitly specified that revoke should remove a `GRANT` or `DENY`, it is also possible to revoke either one by not specifying at all as the next example demonstrates.
          |Because of this, if there happen to be a `GRANT` and a `DENY` on the same privilege, it would remove both.""".stripMargin)
      query("REVOKE TRAVERSE ON GRAPH neo4j NODES Payments TO regularUsers", ResultAssertions((r) => {
        assertStats(r, systemUpdates = 1)
      })) {}
    }
  }.build()

  private def assertPrivilegeShown(expected: Seq[Map[String, AnyRef]]) = ResultAndDbAssertions((p, db) => {
    println(p.resultAsString)
    println(s"Searching for $expected")
    val found = p.toList.filter { row =>
      println(s"Checking row: $row")
      val m = expected.filter { expectedRow =>
        expectedRow.forall {
          case (k, v) => row.contains(k) && row(k) == v
        }
      }
      println(s"\tmatched: $m")
      m.nonEmpty
    }
    found.nonEmpty
  })
}
