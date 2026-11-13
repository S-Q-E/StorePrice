package com.storeprice.app.ui

import com.storeprice.app.domain.model.EmployeeProfile
import com.storeprice.app.domain.model.EmployeeRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAccessTest {

    @Test
    fun `admin online can edit the catalog`() {
        assertTrue(canEditCatalog(employee(EmployeeRole.ADMIN), isOffline = false))
    }

    @Test
    fun `employee cannot edit the catalog even online`() {
        assertFalse(canEditCatalog(employee(EmployeeRole.EMPLOYEE), isOffline = false))
    }

    @Test
    fun `admin cannot edit the catalog offline`() {
        assertFalse(canEditCatalog(employee(EmployeeRole.ADMIN), isOffline = true))
    }

    private fun employee(role: EmployeeRole) = EmployeeProfile(
        id = "employee-id",
        displayName = "Сотрудник",
        phone = null,
        role = role,
        isActive = true,
    )
}
